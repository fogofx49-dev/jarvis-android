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
import org.json.JSONObject
import java.util.Locale
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
    private var processingCommand = false

    private val listeningHandler = Handler(Looper.getMainLooper())
    private val requestRunning = AtomicBoolean(false)

    private val restartListeningRunnable = Runnable {
        if (
            automaticListeningEnabled &&
            !jarvisSpeaking &&
            !processingCommand &&
            !recognitionStarting &&
            pageReady &&
            !isFinishing &&
            !isDestroyed
        ) {
            beginRecognition()
        }
    }

    private var pendingFlashlightState: Boolean? = null

    companion object {
        private const val TAG = "JARVIS_LOCAL"
        private const val MIC_PERMISSION = 101
        private const val FLASH_PERMISSION = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeTts()

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                showStatus("JARVIS online. Sistemas locais ativos.")
                notifyJavascriptEvent("onNativeModelLoaded")
                startAutomaticListening()
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidVoice")

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun callJavascript(script: String) {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun notifyJavascript(functionName: String, value: String) {
        val safeValue = JSONObject.quote(value)
        callJavascript("if (typeof window.$functionName === 'function') window.$functionName($safeValue);")
    }

    private fun notifyFirstAvailable(value: String, vararg functionNames: String) {
        val safeValue = JSONObject.quote(value)
        val checks = functionNames.mapIndexed { index, name ->
            val prefix = if (index == 0) "if" else "else if"
            "$prefix (typeof window.$name === 'function') { window.$name($safeValue); return; }"
        }.joinToString(" ")
        callJavascript("(function() { $checks })();")
    }

    private fun notifyJavascriptEvent(functionName: String) {
        callJavascript("if (typeof window.$functionName === 'function') window.$functionName();")
    }

    // =========================================================
    // TEXT TO SPEECH
    // =========================================================

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureJarvisVoice()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        jarvisSpeaking = true
                        listeningHandler.removeCallbacks(restartListeningRunnable)
                        notifyJavascriptEvent("onNativeSpeechStarted")
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            jarvisSpeaking = false
                            notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished")
                            scheduleListeningRestart(800)
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            jarvisSpeaking = false
                            notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished")
                            scheduleListeningRestart(1000)
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        runOnUiThread {
                            jarvisSpeaking = false
                            notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished")
                            scheduleListeningRestart(1000)
                        }
                    }
                })
            } else {
                ttsReady = false
                showStatus("Erro ao iniciar voz.")
            }
        }
    }

    private fun configureJarvisVoice() {
        val result = tts.setLanguage(Locale("pt", "BR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsReady = false
            return
        }

        val voices = tts.voices ?: emptySet()
        val brazilianVoices = voices.filter { voice ->
            voice.locale.language.equals("pt", ignoreCase = true) &&
                voice.locale.country.equals("BR", ignoreCase = true)
        }

        val selectedVoice = brazilianVoices.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
            ?: brazilianVoices.maxByOrNull { it.quality }

        if (selectedVoice != null) {
            tts.voice = selectedVoice
        }

        tts.setSpeechRate(0.94f)
        tts.setPitch(0.92f)
        ttsReady = true
    }

    private fun speak(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        runOnUiThread {
            if (!ttsReady || isFinishing || isDestroyed) return@runOnUiThread

            jarvisSpeaking = true
            processingCommand = false
            listeningHandler.removeCallbacks(restartListeningRunnable)

            try {
                recognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao cancelar escuta", e)
            }
            recognitionStarting = false

            val result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
            if (result == TextToSpeech.ERROR) {
                jarvisSpeaking = false
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

    // =========================================================
    // ESCUTA AUTOMÁTICA
    // =========================================================

    private fun startAutomaticListening() {
        automaticListeningEnabled = true
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            return
        }
        scheduleListeningRestart(700)
    }

    private fun stopAutomaticListening() {
        automaticListeningEnabled = false
        processingCommand = false
        listeningHandler.removeCallbacks(restartListeningRunnable)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao cancelar reconhecimento", e)
        }
        recognitionStarting = false
    }

    private fun scheduleListeningRestart(delayMillis: Long = 1000) {
        listeningHandler.removeCallbacks(restartListeningRunnable)
        if (automaticListeningEnabled && !jarvisSpeaking && !processingCommand && pageReady && !isFinishing && !isDestroyed) {
            listeningHandler.postDelayed(restartListeningRunnable, delayMillis)
        }
    }

    private fun beginRecognition() {
        if (!automaticListeningEnabled || jarvisSpeaking || processingCommand || recognitionStarting || !pageReady || isFinishing || isDestroyed) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAutomaticListening()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            scheduleListeningRestart(5000)
            return
        }

        recognitionStarting = true
        try {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null

            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    recognitionStarting = false
                    showStatus("Escuta ativa.")
                }
                override fun onBeginningOfSpeech() { showStatus("A ouvir...") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { showStatus("A processar...") }
                override fun onError(error: Int) {
                    recognitionStarting = false
                    if (processingCommand || jarvisSpeaking) return
                    if (automaticListeningEnabled) {
                        scheduleListeningRestart(1500)
                    }
                }
                override fun onResults(results: Bundle?) {
                    recognitionStarting = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull().orEmpty()
                    if (spokenText.isBlank()) {
                        scheduleListeningRestart(700)
                        return
                    }

                    val normalized = spokenText.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().replace(Regex("\\s+"), " ")
                    val commandText = if (normalized.startsWith("jarvis ")) {
                        spokenText.replace(Regex("^\\s*jarvis\\b\\s*[,.:;!?-]*\\s*", RegexOption.IGNORE_CASE), "").trim()
                    } else {
                        spokenText
                    }

                    if (commandText.isBlank()) {
                        speak("Sim, chefe. Estou a escutar.")
                        return
                    }

                    processingCommand = true
                    listeningHandler.removeCallbacks(restartListeningRunnable)
                    showStatus("A processar comando...")

                    notifyJavascript("onNativeSpeechResult", commandText)
                    processJarvisCommand(commandText)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            recognitionStarting = false
            scheduleListeningRestart(2500)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MIC_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startAutomaticListening()
                } else {
                    automaticListeningEnabled = false
                    showStatus("Permissão do microfone negada.")
                }
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    private fun changeMediaVolume(action: String): String {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (action.lowercase(Locale.ROOT)) {
            "up", "aumentar" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "down", "baixar", "reduzir" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "mute", "silenciar" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        val updated = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Volume ajustado para $updated de $max."
    }

    private fun requestFlashlight(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingFlashlightState = enabled
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), FLASH_PERMISSION)
            return
        }
        try {
            showStatus(toggleFlashlight(enabled))
        } catch (e: Exception) {
            Log.e(TAG, "Erro lanterna", e)
        }
    }

    private fun toggleFlashlight(enable: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Requer Android 6.0 ou superior."
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Sensor de lanterna indisponível."
        cameraManager.setTorchMode(cameraId, enable)
        return if (enable) "Lanterna ativada." else "Lanterna desativada."
    }

    // =========================================================
    // MOTOR DE PROCESSAMENTO INTELIGENTE LOCAL (STARK AI)
    // =========================================================

    private fun processJarvisCommand(prompt: String) {
        val text = prompt.lowercase(Locale.ROOT).trim()
        
        val response = when {
            text.contains("bateria") || text.contains("carga") -> {
                val level = getBatteryLevel()
                if (level in 0..100) "A bateria do sistema encontra-se a $level por cento." else "Estado da bateria indisponível."
            }
            text.contains("lanterna") && (text.contains("liga") || text.contains("acende")) -> {
                toggleFlashlight(true)
                "Lanterna ligada, chefe."
            }
            text.contains("lanterna") && (text.contains("desliga") || text.contains("apaga")) -> {
                toggleFlashlight(false)
                "Lanterna desligada."
            }
            text.contains("volume") && text.contains("mais") -> {
                changeMediaVolume("up")
            }
            text.contains("volume") && text.contains("menos") -> {
                changeMediaVolume("down")
            }
            text.contains("abrir youtube") || text.contains("abre o youtube") -> {
                openApplication("youtube")
                "A abrir o YouTube."
            }
            text.contains("abrir whatsapp") || text.contains("abre o whatsapp") -> {
                openApplication("whatsapp")
                "A abrir o WhatsApp."
            }
            text.contains("bom dia") -> {
                val battery = getBatteryLevel()
                "Bom dia, chefe. Todos os sistemas operacionais encontram-se a cem por cento. Bateria em $battery por cento. Como posso auxiliar nas suas tarefas hoje?"
            }
            text.contains("quem é você") || text.contains("qual o seu nome") -> {
                "Sou J.A.R.V.I.S., o seu assistente pessoal inteligente desenvolvido com tecnologia Stark."
            }
            text.contains("status") || text.contains("sistema") -> {
                "Todos os subsistemas holográficos e nativos estão operacionais e em pleno funcionamento."
            }
            else -> {
                // Resposta inteligente genérica com tom Stark
                "Compreendido. A processar o comando localmente com máxima eficiência."
            }
        }

        showCloudResponse(response)
    }

    // =========================================================
    // PONTE JAVASCRIPT / ANDROID
    // =========================================================

    inner class AndroidBridge {
        @JavascriptInterface fun speak(text: String) { this@MainActivity.speak(text) }
        @JavascriptInterface fun stopSpeaking() { this@MainActivity.stopSpeaking() }
        @JavascriptInterface fun openApp(app: String) { runOnUiThread { openApplication(app) } }
        @JavascriptInterface fun startListening() { runOnUiThread { startAutomaticListening() } }
        @JavascriptInterface fun stopListening() { runOnUiThread { stopAutomaticListening() } }
        @JavascriptInterface fun chooseModel() { showStatus("Modo local inteligente ativo.") }
        @JavascriptInterface fun askModel(prompt: String) { processJarvisCommand(prompt) }
        @JavascriptInterface fun askCloudAI(prompt: String) { processJarvisCommand(prompt) }

        @JavascriptInterface
        fun getBattery(): String {
            val level = getBatteryLevel()
            return if (level in 0..100) "A bateria está em $level por cento." else "Consulta de bateria indisponível."
        }

        @JavascriptInterface
        fun changeVolume(action: String): String {
            return try { changeMediaVolume(action) } catch (e: Exception) { "Erro ao alterar volume." }
        }

        @JavascriptInterface fun setFlashlight(enabled: Boolean) { runOnUiThread { requestFlashlight(enabled) } }
        @JavascriptInterface fun openWifiSettings() { runOnUiThread { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }

        @JavascriptInterface
        fun sendEmail(recipient: String, subject: String, body: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$recipient")
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                try { startActivity(intent); showStatus("A abrir o e-mail...") } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun addCalendarEvent(title: String, description: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = android.provider.CalendarContract.Events.CONTENT_URI
                    putExtra(android.provider.CalendarContract.Events.TITLE, title)
                    putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description)
                }
                try { startActivity(intent); showStatus("A agendar no calendário...") } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun sendWhatsApp(phone: String, message: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}")
                }
                try { startActivity(intent); showStatus("A abrir o WhatsApp...") } catch (_: Exception) {}
            }
        }
    }

    private fun openApplication(app: String) {
        try {
            val appName = app.lowercase(Locale.ROOT).trim()
            val intent = when (appName) {
                "youtube" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                "google" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                "settings", "configurações", "configuracoes" -> Intent(Settings.ACTION_SETTINGS)
                "camera", "câmara", "camara" -> Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                else -> {
                    val packageName = when {
                        appName.contains("whatsapp") -> "com.whatsapp"
                        appName.contains("spotify") -> "com.spotify.music"
                        appName.contains("instagram") -> "com.instagram.android"
                        appName.contains("telegram") -> "org.telegram.messenger"
                        appName.contains("facebook") -> "com.facebook.katana"
                        appName.contains("maps") || appName.contains("mapas") -> "com.google.android.apps.maps"
                        else -> null
                    }
                    val launchIntent = if (packageName != null) packageManager.getLaunchIntentForPackage(packageName) else packageManager.getLaunchIntentForPackage(app)
                    launchIntent ?: return
                }
            }
            startActivity(intent)
            showStatus("A abrir $app.")
            speak("A abrir $app.")
        } catch (_: Exception) {}
    }

    private fun showCloudResponse(answer: String) {
        processingCommand = false
        notifyFirstAvailable(answer, "onCloudAIResponse", "onNativeModelResponse", "onModelResponse")
        showStatus("Pronto.")
        speak(answer)
        scheduleListeningRestart(1500)
    }

    private fun showStatus(message: String) {
        notifyJavascript("onNativeStatus", message)
    }

    override fun onDestroy() {
        pageReady = false
        stopAutomaticListening()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
}
