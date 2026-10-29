package com.fogofx.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var ttsReady = false
    private var pendingSpeech = ""
    private var recognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isModelLoaded = false

    private var modelEngine: Engine? = null
    private var modelConversation: Conversation? = null
    private var activeModelName = "Nenhum"

    private var lastSpokenText = ""
    private var lastSpokenTime = 0L

    @Volatile
    private var isLoadingModel = false

    // Modo mãos-livres
    @Volatile
    private var handsFreeEnabled = false

    private var recognitionInProgress = false
    private var waitingForResponse = false
    private var isTtsSpeaking = false
    private var pendingManualListen = false

    private var restartListeningRunnable: Runnable? = null
    private var responseTimeoutRunnable: Runnable? = null

    companion object {
        private const val MIC_PERMISSION = 101
        private const val PICK_MODEL_REQUEST = 1002
        private const val RESPONSE_TIMEOUT_MS = 60000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initializeTts()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    showStatus("Interface pronta. Carrega o modelo de IA.")
                }
            }

            addJavascriptInterface(AndroidBridge(), "AndroidVoice")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    // ---------------------------------------------------------
    // VOZ ANDROID
    // ---------------------------------------------------------

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    val languageResult =
                        tts.setLanguage(Locale("pt", "BR"))

                    tts.setSpeechRate(0.96f)
                    tts.setPitch(0.92f)

                    tts.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {

                            override fun onStart(utteranceId: String?) {
                                mainHandler.post {
                                    if (utteranceId == "JARVIS_VOICE") {
                                        isTtsSpeaking = true
                                        stopRecognitionInternal()
                                        showStatus("J.A.R.V.I.S. a falar...")
                                    }
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                mainHandler.post {
                                    if (utteranceId == "JARVIS_VOICE") {
                                        isTtsSpeaking = false

                                        if (handsFreeEnabled) {
                                            scheduleListeningRestart(700)
                                        }
                                    }
                                }
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            override fun onError(utteranceId: String?) {
                                mainHandler.post {
                                    if (utteranceId == "JARVIS_VOICE") {
                                        isTtsSpeaking = false

                                        if (handsFreeEnabled) {
                                            scheduleListeningRestart(700)
                                        }
                                    }
                                }
                            }

                            override fun onStop(
                                utteranceId: String?,
                                interrupted: Boolean
                            ) {
                                mainHandler.post {
                                    if (utteranceId == "JARVIS_VOICE") {
                                        isTtsSpeaking = false

                                        if (handsFreeEnabled) {
                                            scheduleListeningRestart(700)
                                        }
                                    }
                                }
                            }
                        }
                    )

                    ttsReady =
                        languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                    showStatus(
                        if (ttsReady) {
                            "Voz Android pronta."
                        } else {
                            "Seleciona uma voz portuguesa nas definições do Android."
                        }
                    )

                    if (ttsReady && pendingSpeech.isNotBlank()) {
                        val text = pendingSpeech
                        pendingSpeech = ""
                        speakText(text)
                    }
                } else {
                    ttsReady = false
                    showStatus("Não foi possível iniciar a voz Android.")
                }
            }
        }
    }

    private fun speakText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            finishResponseWithoutSpeech()
            return
        }

        mainHandler.post {
            if (!ttsReady) {
                pendingSpeech = clean
                return@post
            }

            try {
                isTtsSpeaking = true

                val result = tts.speak(
                    clean,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "JARVIS_VOICE"
                )

                if (result == TextToSpeech.ERROR) {
                    isTtsSpeaking = false
                    showStatus("Não foi possível reproduzir a voz.")

                    if (handsFreeEnabled) {
                        scheduleListeningRestart(700)
                    }
                }
            } catch (e: Exception) {
                isTtsSpeaking = false
                showStatus("Erro ao reproduzir a voz.")

                if (handsFreeEnabled) {
                    scheduleListeningRestart(700)
                }
            }
        }
    }

    private fun stopSpeaking() {
        mainHandler.post {
            try {
                if (::tts.isInitialized) {
                    tts.stop()
                }

                pendingSpeech = ""
                isTtsSpeaking = false
            } catch (_: Exception) {
            }
        }
    }

    private fun finishResponseWithoutSpeech() {
        mainHandler.post {
            waitingForResponse = false
            cancelResponseTimeout()

            if (handsFreeEnabled) {
                scheduleListeningRestart(700)
            }
        }
    }

    // ---------------------------------------------------------
    // MODO MÃOS-LIVRES
    // ---------------------------------------------------------

    private fun startHandsFree() {
        mainHandler.post {
            if (handsFreeEnabled) {
                showStatus("O modo mãos-livres já está ativo.")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                showStatus(
                    "O reconhecimento de voz não está disponível neste dispositivo."
                )
                return@post
            }

            handsFreeEnabled = true
            waitingForResponse = false

            notifyHandsFreeState(
                true,
                "Modo mãos-livres ativado."
            )

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showStatus("É necessária a permissão do microfone.")

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    MIC_PERMISSION
                )
                return@post
            }

            showStatus("Modo mãos-livres ativado. A preparar o microfone...")
            scheduleListeningRestart(250)
        }
    }

    private fun stopHandsFree() {
        mainHandler.post {
            handsFreeEnabled = false
            waitingForResponse = false
            pendingManualListen = false

            cancelResponseTimeout()
            stopRecognitionInternal()

            try {
                tts.stop()
            } catch (_: Exception) {
            }

            isTtsSpeaking = false
            pendingSpeech = ""

            notifyHandsFreeState(
                false,
                "Modo mãos-livres desligado."
            )

            showStatus("Modo mãos-livres desligado.")
        }
    }

    private fun notifyHandsFreeState(
        enabled: Boolean,
        message: String
    ) {
        mainHandler.post {
            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeHandsFreeState === 'function') " +
                    "onNativeHandsFreeState($enabled, " +
                    "${JSONObject.quote(message)});",
                null
            )
        }
    }

    private fun scheduleListeningRestart(delayMs: Long) {
        mainHandler.post {
            restartListeningRunnable?.let {
                mainHandler.removeCallbacks(it)
            }

            if (!handsFreeEnabled) return@post

            val task = Runnable {
                if (
                    handsFreeEnabled &&
                    !waitingForResponse &&
                    !recognitionInProgress &&
                    !isTtsSpeaking
                ) {
                    startRecognitionSession(true)
                }
            }

            restartListeningRunnable = task
            mainHandler.postDelayed(task, delayMs)
        }
    }

    private fun cancelResponseTimeout() {
        responseTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        responseTimeoutRunnable = null
    }

    private fun beginResponseWait() {
        waitingForResponse = true
        cancelResponseTimeout()

        val task = Runnable {
            if (handsFreeEnabled && waitingForResponse) {
                waitingForResponse = false
                showStatus(
                    "A resposta demorou demasiado. Vou voltar a ouvir."
                )
                scheduleListeningRestart(900)
            }
        }

        responseTimeoutRunnable = task
        mainHandler.postDelayed(task, RESPONSE_TIMEOUT_MS)
    }

    private fun clearResponseWait() {
        waitingForResponse = false
        cancelResponseTimeout()
    }

    private fun stopRecognitionInternal() {
        restartListeningRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        restartListeningRunnable = null

        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        recognizer = null
        recognitionInProgress = false
    }

    // ---------------------------------------------------------
    // RECONHECIMENTO DE VOZ
    // ---------------------------------------------------------

    private fun startListeningManual() {
        if (handsFreeEnabled) {
            showStatus("O modo mãos-livres já controla o microfone.")
            return
        }

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingManualListen = true

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION
            )
            return
        }

        startRecognitionSession(false)
    }

    private fun startRecognitionSession(handsFree: Boolean) {
        mainHandler.post {
            if (handsFree && !handsFreeEnabled) return@post

            if (
                handsFree &&
                (waitingForResponse || isTtsSpeaking)
            ) {
                return@post
            }

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (handsFree) {
                    showStatus("A permissão do microfone foi retirada.")
                    stopHandsFree()
                } else {
                    startListeningManual()
                }
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                if (handsFree) {
                    showStatus("Reconhecimento de voz indisponível.")
                    stopHandsFree()
                } else {
                    showSpeechError("Reconhecimento de voz indisponível.")
                }
                return@post
            }

            stopRecognitionInternal()

            try {
                recognizer =
                    SpeechRecognizer.createSpeechRecognizer(this)

                recognizer?.setRecognitionListener(
                    object : RecognitionListener {

                        override fun onReadyForSpeech(params: Bundle?) {
                            recognitionInProgress = true
                            showStatus(
                                if (handsFreeEnabled) {
                                    "Mãos-livres: estou a ouvir..."
                                } else {
                                    "A ouvir..."
                                }
                            )
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            showStatus("A processar a voz...")
                        }

                        override fun onError(error: Int) {
                            recognitionInProgress = false

                            try {
                                recognizer?.destroy()
                            } catch (_: Exception) {
                            }
                            recognizer = null

                            if (handsFreeEnabled) {
                                if (
                                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                ) {
                                    showStatus(
                                        "Não percebi. Vou voltar a ouvir..."
                                    )
                                } else {
                                    showStatus(
                                        "O reconhecimento foi interrompido. A tentar novamente..."
                                    )
                                }

                                scheduleListeningRestart(
                                    if (
                                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                    ) 500 else 1200
                                )
                            } else {
                                showSpeechError(
                                    "Reconhecimento de voz terminado (código $error)."
                                )
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            recognitionInProgress = false

                            val text = results
                                ?.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION
                                )
                                ?.firstOrNull()
                                ?.trim()

                            try {
                                recognizer?.destroy()
                            } catch (_: Exception) {
                            }
                            recognizer = null

                            if (!text.isNullOrBlank()) {
                                if (handsFreeEnabled) {
                                    beginResponseWait()
                                }

                                webView.evaluateJavascript(
                                    "if (typeof onNativeSpeechResult === 'function') " +
                                        "onNativeSpeechResult(" +
                                        "${JSONObject.quote(text)}" +
                                        ");",
                                    null
                                )
                            } else {
                                if (handsFreeEnabled) {
                                    showStatus(
                                        "Não reconheci a frase. Vou voltar a ouvir..."
                                    )
                                    scheduleListeningRestart(600)
                                } else {
                                    showSpeechError(
                                        "Não foi reconhecida nenhuma frase."
                                    )
                                }
                            }
                        }

                        override fun onPartialResults(
                            partialResults: Bundle?
                        ) {}

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?
                        ) {}
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
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        1
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )
                }

                recognitionInProgress = true
                recognizer?.startListening(intent)

            } catch (e: Exception) {
                recognitionInProgress = false

                try {
                    recognizer?.destroy()
                } catch (_: Exception) {
                }
                recognizer = null

                if (handsFreeEnabled) {
                    showStatus("Não consegui iniciar o microfone. A tentar novamente...")
                    scheduleListeningRestart(1200)
                } else {
                    showSpeechError(
                        "Não foi possível iniciar o reconhecimento: ${e.message}"
                    )
                }
            }
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

        if (requestCode != MIC_PERMISSION) return

        val granted =
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            pendingManualListen = false

            if (handsFreeEnabled) {
                stopHandsFree()
            }

            showStatus("Permissão do microfone recusada.")
            return
        }

        if (handsFreeEnabled) {
            showStatus("Permissão concedida. A iniciar a escuta...")
            scheduleListeningRestart(250)
        } else if (pendingManualListen) {
            pendingManualListen = false
            startListeningManual()
        }
    }

    // ---------------------------------------------------------
    // SELETOR E CÓPIA DO MODELO
    // ---------------------------------------------------------

    private fun openModelFileSelector() {
        mainHandler.post {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }

            try {
                startActivityForResult(intent, PICK_MODEL_REQUEST)
                showStatus("Seleciona o ficheiro .litertlm...")
            } catch (e: Exception) {
                showModelError(
                    "Não foi possível abrir o seletor: ${e.message}"
                )
            }
        }
    }

    @Deprecated("Usado para manter compatibilidade com o projeto atual")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode != PICK_MODEL_REQUEST ||
            resultCode != RESULT_OK
        ) {
            if (requestCode == PICK_MODEL_REQUEST) {
                showStatus("Seleção do modelo cancelada.")
            }
            return
        }

        val uri = data?.data ?: return

        backgroundExecutor.execute {
            try {
                val originalName = getDisplayName(uri)

                if (!originalName.endsWith(".litertlm", ignoreCase = true)) {
                    showModelError(
                        "Seleciona um ficheiro com extensão .litertlm."
                    )
                    return@execute
                }

                val destination = File(filesDir, "model.litertlm")

                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        throw IllegalStateException(
                            "Não foi possível ler o ficheiro."
                        )
                    }

                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }

                if (!destination.exists() || destination.length() == 0L) {
                    throw IllegalStateException(
                        "O ficheiro copiado está vazio."
                    )
                }

                activeModelName = originalName
                initializeLocalModel(destination)

            } catch (e: Exception) {
                showModelError(
                    "Falha ao copiar ou carregar o modelo: " +
                        (e.message ?: "erro desconhecido")
                )
            }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        return contentResolver.query(
            uri,
            null,
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(
                OpenableColumns.DISPLAY_NAME
            )

            if (cursor.moveToFirst() && index >= 0) {
                cursor.getString(index)
            } else {
                "model.litertlm"
            }
        } ?: "model.litertlm"
    }

    // ---------------------------------------------------------
    // GEMMA LOCAL
    // ---------------------------------------------------------

    private fun initializeLocalModel(file: File) {
        if (isLoadingModel) {
            showStatus("O modelo já está a carregar.")
            return
        }

        isLoadingModel = true
        isModelLoaded = false

        showStatus("A inicializar o Gemma local. Aguarda...")

        try {
            modelConversation?.close()
        } catch (_: Exception) {
        }

        modelConversation = null

        try {
            modelEngine?.close()
        } catch (_: Exception) {
        }

        modelEngine = null

        try {
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU()
            )

            val newEngine = Engine(config)
            newEngine.initialize()

            val newConversation = newEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        """
                        Tu és J.A.R.V.I.S., um assistente útil,
                        educado e conciso.
                        Responde em português natural,
                        preferencialmente português do Brasil.
                        Responde diretamente à pergunta do utilizador.
                        Não afirmes que executaste ações no dispositivo
                        se elas não tiverem sido realmente executadas.
                        """.trimIndent()
                    )
                )
            )

            modelEngine = newEngine
            modelConversation = newConversation
            isModelLoaded = true
            isLoadingModel = false

            mainHandler.post {
                webView.evaluateJavascript(
                    "if (typeof onNativeModelLoaded === 'function') " +
                        "onNativeModelLoaded(" +
                        "${JSONObject.quote("Gemma local pronto: $activeModelName")}" +
                        ");",
                    null
                )

                showStatus("Gemma local pronto.")
            }

        } catch (e: Exception) {
            isLoadingModel = false
            isModelLoaded = false

            try {
                modelConversation?.close()
            } catch (_: Exception) {
            }

            try {
                modelEngine?.close()
            } catch (_: Exception) {
            }

            modelConversation = null
            modelEngine = null

            showModelError(
                "Não foi possível inicializar o Gemma: " +
                    (e.message ?: "erro desconhecido")
            )
        }
    }

    // ---------------------------------------------------------
    // COMANDOS ANDROID
    // ---------------------------------------------------------

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

    private fun toggleFlashlight(enable: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "A lanterna requer Android 6.0 ou superior."
        }

        return try {
            val cameraManager =
                getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Não encontrei uma lanterna disponível."

            cameraManager.setTorchMode(cameraId, enable)

            if (enable) {
                "Lanterna ligada."
            } else {
                "Lanterna desligada."
            }
        } catch (e: Exception) {
            "Não foi possível controlar a lanterna: ${e.message}"
        }
    }

    private fun openApplication(appNameInput: String): String {
        val appName = appNameInput.lowercase(Locale.ROOT).trim()

        return try {
            val intent = when (appName) {
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
                "configuracoes" -> Intent(Settings.ACTION_SETTINGS)

                else -> packageManager.getLaunchIntentForPackage(
                    appNameInput
                ) ?: return "Não encontrei o aplicativo $appNameInput."
            }

            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "A abrir $appName."
        } catch (_: Exception) {
            "Não foi possível abrir $appName."
        }
    }

    private fun processCommand(prompt: String) {
        val text = prompt.lowercase(Locale.ROOT).trim()

        val hardwareAnswer: String? = when {
            text.contains("bateria") -> {
                val level = getBatteryLevel()

                if (level >= 0) {
                    "A bateria está a $level por cento."
                } else {
                    "Não consegui consultar a bateria."
                }
            }

            text.contains("lanterna") &&
                (text.contains("liga") || text.contains("acende")) ->
                toggleFlashlight(true)

            text.contains("lanterna") &&
                (text.contains("desliga") || text.contains("apaga")) ->
                toggleFlashlight(false)

            text.contains("horas") -> {
                "Agora são ${
                    SimpleDateFormat(
                        "HH:mm",
                        Locale("pt", "BR")
                    ).format(Date())
                }."
            }

            text == "abre youtube" ||
                text == "abre o youtube" ->
                openApplication("youtube")

            text == "abre google" ||
                text == "abre o google" ->
                openApplication("google")

            text == "abre as configurações" ||
                text == "abre as configuracoes" ->
                openApplication("settings")

            text.contains("piada") ->
                "Porque é que o computador foi ao médico? Porque tinha um vírus!"

            else -> null
        }

        if (hardwareAnswer != null) {
            respond(hardwareAnswer)
            return
        }

        if (!isModelLoaded || modelConversation == null) {
            respond(
                "O modelo de IA ainda não está pronto. " +
                    "Carrega um ficheiro .litertlm válido e aguarda a inicialização."
            )
            return
        }

        showStatus("A processar no Gemma local...")

        backgroundExecutor.execute {
            try {
                val conversation = modelConversation
                    ?: throw IllegalStateException(
                        "A conversa do modelo não está disponível."
                    )

                val result = conversation.sendMessage(prompt)
                val response = result.toString().trim()

                if (response.isBlank()) {
                    throw IllegalStateException(
                        "O modelo devolveu uma resposta vazia."
                    )
                }

                respond(response)

            } catch (e: Exception) {
                showModelError(
                    "Erro ao gerar resposta: " +
                        (e.message ?: "erro desconhecido")
                )
            }
        }
    }

    // ---------------------------------------------------------
    // RESPOSTAS E ESTADOS
    // ---------------------------------------------------------

    private fun respond(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        val now = System.currentTimeMillis()

        if (
            clean == lastSpokenText &&
            now - lastSpokenTime < 1500
        ) {
            clearResponseWait()
            return
        }

        lastSpokenText = clean
        lastSpokenTime = now

        clearResponseWait()

        mainHandler.post {
            webView.evaluateJavascript(
                "if (typeof onNativeModelResponse === 'function') " +
                    "onNativeModelResponse(${JSONObject.quote(clean)});",
                null
            )

            showStatus("Resposta pronta.")
        }
    }

    private fun showStatus(message: String) {
        mainHandler.post {
            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeStatus === 'function') " +
                    "onNativeStatus(${JSONObject.quote(message)});",
                null
            )
        }
    }

    private fun showModelError(message: String) {
        clearResponseWait()

        mainHandler.post {
            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeModelError === 'function') " +
                    "onNativeModelError(${JSONObject.quote(message)});",
                null
            )

            if (handsFreeEnabled) {
                scheduleListeningRestart(900)
            }
        }
    }

    private fun showSpeechError(message: String) {
        mainHandler.post {
            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeSpeechError === 'function') " +
                    "onNativeSpeechError(${JSONObject.quote(message)});",
                null
            )
        }
    }

    // ---------------------------------------------------------
    // PONTE ANDROID / JAVASCRIPT
    // ---------------------------------------------------------

    inner class AndroidBridge {

        @JavascriptInterface
        fun askModel(prompt: String) {
            processCommand(prompt)
        }

        @JavascriptInterface
        fun startListening() {
            startListeningManual()
        }

        @JavascriptInterface
        fun startHandsFree() {
            this@MainActivity.startHandsFree()
        }

        @JavascriptInterface
        fun stopHandsFree() {
            this@MainActivity.stopHandsFree()
        }

        @JavascriptInterface
        fun responseFinished() {
            finishResponseWithoutSpeech()
        }

        @JavascriptInterface
        fun chooseModel() {
            openModelFileSelector()
        }

        @JavascriptInterface
        fun speak(text: String) {
            mainHandler.post {
                clearResponseWait()
                speakText(text)
            }
        }

        @JavascriptInterface
        fun stopSpeaking() {
            this@MainActivity.stopSpeaking()
        }

        @JavascriptInterface
        fun openApp(appName: String) {
            val result = openApplication(appName)
            respond(result)
        }
    }

    override fun onDestroy() {
        handsFreeEnabled = false
        waitingForResponse = false

        cancelResponseTimeout()
        stopRecognitionInternal()

        try {
            tts.stop()
        } catch (_: Exception) {
        }

        backgroundExecutor.execute {
            try {
                modelConversation?.close()
            } catch (_: Exception) {
            }

            try {
                modelEngine?.close()
            } catch (_: Exception) {
            }

            modelConversation = null
            modelEngine = null
        }

        backgroundExecutor.shutdown()

        if (::tts.isInitialized) {
            tts.shutdown()
        }

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidVoice")
            webView.destroy()
        }

        super.onDestroy()
    }
}
