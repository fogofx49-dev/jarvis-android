package com.fogofx.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var automaticListeningEnabled = false

    @Volatile
    private var jarvisSpeaking = false

    @Volatile
    private var recognitionStarting = false

    @Volatile
    private var pageReady = false

    @Volatile
    private var waitingForModel = false

    // false: usa o serviço de reconhecimento padrão do Android.
    // O serviço pode precisar de internet.
    private val preferOfflineSpeech = false

    private val listeningHandler = Handler(Looper.getMainLooper())

    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val modelRequestRunning = AtomicBoolean(false)

    private val restartListeningRunnable = Runnable {
        if (
            automaticListeningEnabled &&
            !jarvisSpeaking &&
            !waitingForModel &&
            !recognitionStarting &&
            pageReady &&
            !isFinishing &&
            !isDestroyed
        ) {
            beginRecognition()
        }
    }

    private var modelUri: Uri? = null
    private var modelFile: File? = null

    @Volatile
    private var modelEngine: Engine? = null

    @Volatile
    private var modelConversation: Conversation? = null

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelLoading = false

    private var pendingFlashlightState: Boolean? = null

    companion object {
        private const val TAG = "JARVIS"
        private const val MODEL_TAG = "JARVIS_MODEL"

        private const val MIC_PERMISSION = 101
        private const val MODEL_PICKER = 102
        private const val FLASH_PERMISSION = 103

        private const val MODEL_FILENAME = "model.litertlm"
        private const val PREFERRED_VOICE_ID = ""

        private const val SYSTEM_INSTRUCTION = """
            Você é J.A.R.V.I.S., um assistente virtual pessoal.
            Responda em português do Brasil, de forma natural,
            educada, clara e útil.
            Seja objetivo, mas explique os passos quando necessário.
            Não afirme que executou ações que não executou.
            Quando não souber uma resposta, seja transparente.
            Em conversas espontâneas, use frases naturais e breves.
        """
    }

    // =========================================================
    // INICIALIZAÇÃO
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initializeTts()

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                pageReady = true

                showStatus(
                    if (modelReady) {
                        "JARVIS pronto. Modelo local carregado."
                    } else {
                        "Interface pronta. A verificar o modelo local..."
                    }
                )

                initializeGemma()
                startAutomaticListening()
            }
        }

        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(
            AndroidBridge(),
            "AndroidVoice"
        )

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    // =========================================================
    // COMUNICAÇÃO COM O HTML
    // =========================================================

    private fun callJavascript(script: String) {
        if (!::webView.isInitialized || isFinishing || isDestroyed) {
            return
        }

        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun notifyJavascript(
        functionName: String,
        value: String
    ) {
        val safeValue = JSONObject.quote(value)

        callJavascript(
            "if (typeof window.$functionName === 'function') " +
                "window.$functionName($safeValue);"
        )
    }

    /*
     * Chama apenas o primeiro evento existente no HTML.
     * Evita falar duas vezes quando o HTML contém os nomes
     * antigos e novos para o mesmo evento.
     */
    private fun notifyFirstAvailable(
        value: String,
        vararg functionNames: String
    ) {
        val safeValue = JSONObject.quote(value)

        val checks = functionNames.mapIndexed { index, name ->
            val prefix = if (index == 0) "if" else "else if"

            "$prefix (typeof window.$name === 'function') {" +
                "window.$name($safeValue); return;" +
                "}"
        }.joinToString(" ")

        callJavascript(
            "(function() { $checks })();"
        )
    }

    private fun notifyJavascriptEvent(functionName: String) {
        callJavascript(
            "if (typeof window.$functionName === 'function') " +
                "window.$functionName();"
        )
    }

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureJarvisVoice()

                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {
                            jarvisSpeaking = true

                            listeningHandler.removeCallbacks(
                                restartListeningRunnable
                            )

                            notifyJavascriptEvent("onNativeSpeechStarted")
                        }

                        override fun onDone(utteranceId: String?) {
                            runOnUiThread {
                                jarvisSpeaking = false

                                notifyFirstAvailable(
                                    "",
                                    "onNativeSpeechFinished",
                                    "onSpeechFinished"
                                )

                                scheduleListeningRestart(800)
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                jarvisSpeaking = false

                                notifyFirstAvailable(
                                    "",
                                    "onNativeSpeechFinished",
                                    "onSpeechFinished"
                                )

                                scheduleListeningRestart(1000)
                            }
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int
                        ) {
                            Log.w(TAG, "Erro TTS: $errorCode")

                            runOnUiThread {
                                jarvisSpeaking = false

                                notifyFirstAvailable(
                                    "",
                                    "onNativeSpeechFinished",
                                    "onSpeechFinished"
                                )

                                scheduleListeningRestart(1000)
                            }
                        }
                    }
                )
            } else {
                ttsReady = false
                showStatus("Não foi possível iniciar a voz.")
                Log.e(TAG, "Falha ao inicializar TextToSpeech: $status")
            }
        }
    }

    private fun configureJarvisVoice() {
        val result = tts.setLanguage(Locale("pt", "BR"))

        if (
            result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            ttsReady = false
            showStatus(
                "A voz em português do Brasil não está disponível."
            )
            return
        }

        val voices = tts.voices ?: emptySet<Voice>()

        val brazilianVoices = voices.filter { voice ->
            voice.locale.language.equals("pt", ignoreCase = true) &&
                voice.locale.country.equals("BR", ignoreCase = true)
        }

        Log.d(
            TAG,
            "Vozes pt-BR encontradas: ${brazilianVoices.size}"
        )

        brazilianVoices.forEach { voice ->
            Log.d(
                TAG,
                "Voz: ${voice.name}, " +
                    "qualidade=${voice.quality}, " +
                    "rede=${voice.isNetworkConnectionRequired}"
            )
        }

        val preferredVoice = if (PREFERRED_VOICE_ID.isNotBlank()) {
            voices.find { it.name == PREFERRED_VOICE_ID }
        } else {
            null
        }

        val selectedVoice = preferredVoice
            ?: brazilianVoices
                .filter { !it.isNetworkConnectionRequired }
                .maxByOrNull { it.quality }
            ?: brazilianVoices.maxByOrNull { it.quality }

        if (selectedVoice != null) {
            tts.voice = selectedVoice
            Log.d(TAG, "Voz selecionada: ${selectedVoice.name}")
        } else {
            Log.w(TAG, "Não foi encontrada uma voz específica pt-BR.")
        }

        tts.setSpeechRate(0.94f)
        tts.setPitch(0.92f)

        ttsReady = true
        showStatus("Voz do JARVIS pronta.")
    }

    private fun speak(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        runOnUiThread {
            if (!ttsReady || isFinishing || isDestroyed) {
                showStatus("A voz ainda não está pronta.")
                return@runOnUiThread
            }

            jarvisSpeaking = true
            waitingForModel = false

            listeningHandler.removeCallbacks(
                restartListeningRunnable
            )

            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível cancelar a escuta.", e)
            }

            recognitionStarting = false

            val result = tts.speak(
                cleanText,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "JARVIS_UTTERANCE"
            )

            if (result == TextToSpeech.ERROR) {
                jarvisSpeaking = false

                showStatus("Não foi possível reproduzir a fala.")
                notifyJavascriptEvent("onNativeSpeechFinished")
                scheduleListeningRestart(1000)
            }
        }
    }

    private fun stopSpeaking() {
        if (!::tts.isInitialized) return

        runOnUiThread {
            tts.stop()
            jarvisSpeaking = false

            notifyJavascriptEvent("onNativeSpeechFinished")
            scheduleListeningRestart(800)
        }
    }

    private fun listVoices(): String {
        if (!::tts.isInitialized) return "[]"

        val result = JSONArray()
        val voices = tts.voices ?: emptySet<Voice>()

        for (voice in voices) {
            val item = JSONObject()

            item.put("name", voice.name)
            item.put("locale", voice.locale.toLanguageTag())
            item.put("language", voice.locale.toLanguageTag())
            item.put("quality", voice.quality)
            item.put("latency", voice.latency)

            item.put(
                "network",
                if (voice.isNetworkConnectionRequired) {
                    "Requer internet"
                } else {
                    "Disponível offline"
                }
            )

            result.put(item)
        }

        return result.toString()
    }

    // =========================================================
    // ESCUTA AUTOMÁTICA
    // =========================================================

    private fun startAutomaticListening() {
        automaticListeningEnabled = true

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION
            )
            return
        }

        scheduleListeningRestart(700)
    }

    private fun stopAutomaticListening() {
        automaticListeningEnabled = false
        waitingForModel = false

        listeningHandler.removeCallbacks(
            restartListeningRunnable
        )

        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao cancelar o reconhecimento.", e)
        }

        recognitionStarting = false
    }

    private fun scheduleListeningRestart(
        delayMillis: Long = 1000
    ) {
        listeningHandler.removeCallbacks(
            restartListeningRunnable
        )

        if (
            automaticListeningEnabled &&
            !jarvisSpeaking &&
            !waitingForModel &&
            pageReady &&
            !isFinishing &&
            !isDestroyed
        ) {
            listeningHandler.postDelayed(
                restartListeningRunnable,
                delayMillis
            )
        }
    }

    private fun beginRecognition() {
        if (
            !automaticListeningEnabled ||
            jarvisSpeaking ||
            waitingForModel ||
            recognitionStarting ||
            !pageReady ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            startAutomaticListening()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus(
                "Reconhecimento indisponível. Verifica os serviços de voz."
            )

            scheduleListeningRestart(5000)
            return
        }

        recognitionStarting = true

        try {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null

            val localAvailable =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

            recognizer = if (preferOfflineSpeech && localAvailable) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        recognitionStarting = false
                        showStatus("Escuta automática ativa.")
                    }

                    override fun onBeginningOfSpeech() {
                        showStatus("Estou a ouvir...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        showStatus("A processar a fala...")
                    }

                    override fun onError(error: Int) {
                        recognitionStarting = false

                        Log.e(
                            TAG,
                            "Erro de reconhecimento: código=$error"
                        )

                        if (waitingForModel || jarvisSpeaking) {
                            return
                        }

                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO ->
                                "Erro ao capturar o áudio."

                            SpeechRecognizer.ERROR_CLIENT ->
                                "O reconhecimento foi interrompido."

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "A permissão do microfone é necessária."

                            SpeechRecognizer.ERROR_NETWORK ->
                                "Erro de rede no reconhecimento."

                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "A ligação demorou demasiado."

                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "Não consegui perceber a frase."

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "O reconhecimento está ocupado."

                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Não detetei fala."

                            SpeechRecognizer.ERROR_SERVER ->
                                "O serviço de reconhecimento falhou."

                            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
                                "Demasiadas tentativas. Vou aguardar."

                            else ->
                                "Erro de reconhecimento (código $error)."
                        }

                        showStatus(message)

                        if (automaticListeningEnabled) {
                            val delay = when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 3000L
                                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 5000L
                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 3000L
                                else -> 1500L
                            }

                            scheduleListeningRestart(delay)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        recognitionStarting = false

                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val spokenText = matches?.firstOrNull().orEmpty()

                        if (spokenText.isBlank()) {
                            scheduleListeningRestart(700)
                            return
                        }

                        Log.d(TAG, "Texto reconhecido: $spokenText")

                        val normalized = spokenText
                            .lowercase(Locale.ROOT)
                            .replace(
                                Regex("[^\\p{L}\\p{N}\\s]"),
                                " "
                            )
                            .trim()
                            .replace(Regex("\\s+"), " ")

                        if (
                            normalized == "jarvis" ||
                            normalized == "jarvis por favor"
                        ) {
                            speak("Sim, estou aqui.")
                            return
                        }

                        val commandText = if (
                            normalized.startsWith("jarvis ")
                        ) {
                            spokenText.replace(
                                Regex(
                                    "^\\s*jarvis\\b\\s*[,.:;!?-]*\\s*",
                                    RegexOption.IGNORE_CASE
                                ),
                                ""
                            ).trim()
                        } else {
                            spokenText
                        }

                        if (commandText.isBlank()) {
                            speak("Sim, estou aqui.")
                            return
                        }

                        // Bloquear a escuta antes de enviar o texto ao HTML.
                        waitingForModel = true

                        listeningHandler.removeCallbacks(
                            restartListeningRunnable
                        )

                        showStatus("A enviar a pergunta ao Gemma local...")

                        notifyJavascript(
                            "onNativeSpeechResult",
                            commandText
                        )
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {
                    }

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {
                    }
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "pt-BR"
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "pt-BR"
                )

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    false
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {
            recognitionStarting = false

            Log.e(
                TAG,
                "Erro ao iniciar reconhecimento.",
                e
            )

            showStatus(
                "Não foi possível iniciar o reconhecimento de voz."
            )

            scheduleListeningRestart(2500)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {
            MIC_PERMISSION -> {
                if (
                    grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    startAutomaticListening()
                } else {
                    automaticListeningEnabled = false

                    showStatus(
                        "Permite o microfone para usar a escuta automática."
                    )

                    notifyJavascript(
                        "onNativeSpeechError",
                        "A permissão do microfone foi recusada."
                    )
                }
            }

            FLASH_PERMISSION -> {
                val granted =
                    grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED

                val requestedState = pendingFlashlightState
                pendingFlashlightState = null

                if (granted && requestedState != null) {
                    requestFlashlight(requestedState)
                } else {
                    showStatus(
                        "Permissão da câmara recusada. " +
                            "A lanterna não foi alterada."
                    )
                }
            }
        }
    }

    // =========================================================
    // BATERIA
    // =========================================================

    private fun getBatteryLevel(): Int {
        val battery = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        return battery?.getIntExtra(
            BatteryManager.EXTRA_LEVEL,
            -1
        ) ?: -1
    }

    // =========================================================
    // VOLUME MULTIMÉDIA
    // =========================================================

    private fun changeMediaVolume(action: String): String {
        val audio = getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

        val max = audio.getStreamMaxVolume(
            AudioManager.STREAM_MUSIC
        )

        when (action.lowercase(Locale.ROOT)) {
            "up" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                0
            )

            "down" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                0
            )

            "mute" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0
            )

            "unmute" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE,
                0
            )

            else -> return "Comando de volume desconhecido."
        }

        val updated = audio.getStreamVolume(
            AudioManager.STREAM_MUSIC
        )

        return "Volume multimédia: $updated de $max."
    }

    // =========================================================
    // WI-FI
    // =========================================================

    private fun openWifiSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir definições de Wi-Fi.", e)

            try {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                )
            } catch (_: Exception) {
                showStatus(
                    "Não foi possível abrir as definições."
                )
            }
        }
    }

    // =========================================================
    // LANTERNA
    // =========================================================

    private fun requestFlashlight(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            showStatus(
                "A lanterna requer Android 6.0 ou superior."
            )
            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingFlashlightState = enabled

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                FLASH_PERMISSION
            )

            showStatus(
                "Autoriza a câmara para controlar a lanterna."
            )
            return
        }

        try {
            showStatus(toggleFlashlight(enabled))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao controlar a lanterna.", e)
            showStatus("Não foi possível controlar a lanterna.")
        }
    }

    private fun toggleFlashlight(enable: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "A lanterna requer Android 6.0 ou superior."
        }

        val cameraManager = getSystemService(
            Context.CAMERA_SERVICE
        ) as CameraManager

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este dispositivo não disponibiliza uma lanterna compatível."

        cameraManager.setTorchMode(cameraId, enable)

        return if (enable) {
            "Lanterna ligada."
        } else {
            "Lanterna desligada."
        }
    }

    // =========================================================
    // PONTE JAVASCRIPT / ANDROID
    // =========================================================

    inner class AndroidBridge {

        @JavascriptInterface
        fun listVoices() {
            notifyJavascript(
                "onNativeVoicesListed",
                this@MainActivity.listVoices()
            )
        }

        @JavascriptInterface
        fun speak(text: String) {
            this@MainActivity.speak(text)
        }

        @JavascriptInterface
        fun stopSpeaking() {
            this@MainActivity.stopSpeaking()
        }

        @JavascriptInterface
        fun openApp(app: String) {
            runOnUiThread {
                openApplication(app)
            }
        }

        @JavascriptInterface
        fun startListening() {
            runOnUiThread {
                startAutomaticListening()
            }
        }

        @JavascriptInterface
        fun stopListening() {
            runOnUiThread {
                stopAutomaticListening()
            }
        }

        @JavascriptInterface
        fun chooseModel() {
            runOnUiThread {
                openModelPicker()
            }
        }

        @JavascriptInterface
        fun askModel(prompt: String) {
            this@MainActivity.askModel(prompt)
        }

        @JavascriptInterface
        fun getBattery(): String {
            val level = getBatteryLevel()

            return if (level in 0..100) {
                "A bateria está em $level por cento."
            } else {
                "Não foi possível consultar a bateria."
            }
        }

        @JavascriptInterface
        fun changeVolume(action: String): String {
            return try {
                this@MainActivity.changeMediaVolume(action)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao alterar volume.", e)
                "Não foi possível alterar o volume."
            }
        }

        @JavascriptInterface
        fun setFlashlight(enabled: Boolean) {
            runOnUiThread {
                requestFlashlight(enabled)
            }
        }

        @JavascriptInterface
        fun openWifiSettings() {
            runOnUiThread {
                this@MainActivity.openWifiSettings()
            }
        }
    }

    // =========================================================
    // ABRIR APLICAÇÕES
    // =========================================================

    private fun openApplication(app: String) {
        try {
            val intent = when (app.lowercase(Locale.ROOT)) {
                "youtube" -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com")
                )

                "google" -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com")
                )

                "settings",
                "configurações",
                "configuracoes" -> Intent(
                    Settings.ACTION_SETTINGS
                )

                "camera",
                "camara",
                "câmara" -> Intent(
                    android.provider.MediaStore.ACTION_IMAGE_CAPTURE
                )

                else -> {
                    showStatus(
                        "Não reconheço essa aplicação: $app"
                    )
                    return
                }
            }

            startActivity(intent)
            showStatus("A abrir $app.")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir aplicação.", e)
            showStatus("Não consegui abrir $app.")
        }
    }

    // =========================================================
    // MODELO LOCAL LITERT-LM
    // =========================================================

    private fun openModelPicker() {
        try {
            val intent = Intent(
                Intent.ACTION_OPEN_DOCUMENT
            ).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }

            startActivityForResult(
                intent,
                MODEL_PICKER
            )

        } catch (e: Exception) {
            Log.e(MODEL_TAG, "Erro ao abrir seletor.", e)

            showModelError(
                "Não consegui abrir o seletor de ficheiros."
            )
        }
    }

    @Deprecated("Usado para compatibilidade com o seletor de ficheiros.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode != MODEL_PICKER ||
            resultCode != RESULT_OK
        ) {
            return
        }

        val uri = data?.data ?: return
        modelUri = uri

        showStatus("A copiar o ficheiro do modelo...")

        Thread {
            var temporaryFile: File? = null

            try {
                temporaryFile = File(
                    filesDir,
                    "$MODEL_FILENAME.tmp"
                )

                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        throw IOException(
                            "Não foi possível abrir o ficheiro selecionado."
                        )
                    }

                    FileOutputStream(temporaryFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val copiedFile = temporaryFile

                aiExecutor.execute {
                    modelLoading = true
                    modelReady = false

                    try {
                        closeCurrentModel()

                        val destination = File(
                            filesDir,
                            MODEL_FILENAME
                        )

                        if (destination.exists()) {
                            destination.delete()
                        }

                        if (!copiedFile.renameTo(destination)) {
                            copiedFile.copyTo(
                                destination,
                                overwrite = true
                            )
                            copiedFile.delete()
                        }

                        modelFile = destination
                        initializeGemmaOnExecutor(destination)

                    } catch (e: Exception) {
                        Log.e(
                            MODEL_TAG,
                            "Erro ao instalar o modelo.",
                            e
                        )

                        modelLoading = false
                        modelReady = false

                        showModelError(
                            "Não consegui carregar o modelo: " +
                                (e.message ?: "erro desconhecido")
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(
                    MODEL_TAG,
                    "Erro ao copiar o modelo.",
                    e
                )

                temporaryFile?.delete()

                showModelError(
                    "Erro ao copiar o ficheiro: " +
                        (e.message ?: "erro desconhecido")
                )
            }
        }.start()
    }

    private fun initializeGemma() {
        val file = File(
            filesDir,
            MODEL_FILENAME
        )

        modelFile = file

        if (!file.exists()) {
            showStatus(
                "Seleciona um modelo .litertlm para ativar a IA local."
            )
            return
        }

        if (modelReady || modelLoading) return

        modelLoading = true
        showStatus("A iniciar o modelo local...")

        aiExecutor.execute {
            try {
                initializeGemmaOnExecutor(file)

            } catch (e: Exception) {
                Log.e(
                    MODEL_TAG,
                    "Erro ao iniciar o modelo local.",
                    e
                )

                modelReady = false
                modelLoading = false

                showModelError(
                    "Não consegui iniciar o modelo: " +
                        (e.message ?: "erro desconhecido")
                )
            }
        }
    }

    private fun initializeGemmaOnExecutor(file: File) {
        var newEngine: Engine? = null
        var newConversation: Conversation? = null

        try {
            showStatus("A carregar a IA local...")

            val engineConfig = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU()
            )

            newEngine = Engine(engineConfig)
            newEngine.initialize()

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(
                    SYSTEM_INSTRUCTION
                )
            )

            newConversation = newEngine.createConversation(
                conversationConfig
            )

            modelEngine = newEngine
            modelConversation = newConversation

            modelReady = true
            modelLoading = false

            showStatus(
                "JARVIS pronto. Modelo local carregado."
            )

            notifyJavascriptEvent("onNativeModelLoaded")

        } catch (e: Exception) {
            try {
                newConversation?.close()
            } catch (_: Exception) {
            }

            try {
                newEngine?.close()
            } catch (_: Exception) {
            }

            modelReady = false
            modelLoading = false

            throw e
        }
    }

    private fun askModel(prompt: String) {
        val question = prompt.trim()

        if (question.isBlank()) {
            showModelError(
                "Não recebi uma pergunta válida."
            )
            return
        }

        waitingForModel = true

        listeningHandler.removeCallbacks(
            restartListeningRunnable
        )

        if (!modelReady || modelConversation == null) {
            showModelError(
                if (modelLoading) {
                    "O modelo ainda está a carregar."
                } else {
                    "Seleciona primeiro um modelo .litertlm."
                }
            )
            return
        }

        if (!modelRequestRunning.compareAndSet(false, true)) {
            showModelError(
                "O JARVIS ainda está a processar a mensagem anterior."
            )
            return
        }

        showStatus("JARVIS está a pensar...")

        aiExecutor.execute {
            try {
                val conversation = modelConversation
                    ?: throw IllegalStateException(
                        "A conversa com o modelo não está disponível."
                    )

                val result = conversation.sendMessage(
                    question,
                    maxOutputToken = 512
                )

                val answer = result.toString().trim()

                showModelResponse(
                    answer.ifBlank {
                        "Não consegui gerar uma resposta. Tenta novamente."
                    }
                )

            } catch (e: Exception) {
                Log.e(
                    MODEL_TAG,
                    "Erro ao consultar o modelo.",
                    e
                )

                showModelError(
                    "Erro ao consultar a IA: " +
                        (e.message ?: "erro desconhecido")
                )

            } finally {
                modelRequestRunning.set(false)
            }
        }
    }

    private fun closeCurrentModel() {
        try {
            modelConversation?.close()
        } catch (e: Exception) {
            Log.w(
                MODEL_TAG,
                "Erro ao fechar a conversa.",
                e
            )
        } finally {
            modelConversation = null
        }

        try {
            modelEngine?.close()
        } catch (e: Exception) {
            Log.w(
                MODEL_TAG,
                "Erro ao fechar o motor do modelo.",
                e
            )
        } finally {
            modelEngine = null
        }

        modelReady = false
    }

    // =========================================================
    // RESPOSTAS PARA O HTML
    // =========================================================

    private fun showModelResponse(answer: String) {
        waitingForModel = false

        notifyFirstAvailable(
            answer,
            "onNativeModelResponse",
            "onModelResponse"
        )

        showStatus("Resposta do JARVIS pronta.")

        scheduleListeningRestart(1500)
    }

    private fun showModelError(message: String) {
        waitingForModel = false

        notifyFirstAvailable(
            message,
            "onNativeModelError",
            "onModelError"
        )

        showStatus(message)
        scheduleListeningRestart(1500)
    }

    private fun showStatus(message: String) {
        notifyJavascript(
            "onNativeStatus",
            message
        )
    }

    // =========================================================
    // ENCERRAMENTO
    // =========================================================

    override fun onDestroy() {
        pageReady = false
        stopAutomaticListening()

        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        recognizer = null

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        try {
            aiExecutor.execute {
                closeCurrentModel()
            }

            aiExecutor.shutdown()

        } catch (e: Exception) {
            Log.w(
                MODEL_TAG,
                "Não foi possível fechar o modelo normalmente.",
                e
            )

            aiExecutor.shutdownNow()
        }

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
