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
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
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
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var ttsReady = false
    private var pendingSpeech = ""
    private var recognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val requestLock = Any()

    @Volatile
    private var requestInProgress = false

    @Volatile
    private var requestGeneration = 0

    @Volatile
    private var isModelLoaded = false

    @Volatile
    private var isLoadingModel = false

    private var modelEngine: Engine? = null
    private var modelConversation: Conversation? = null
    private var activeModelName = "Nenhum"

    // GEMINI ONLINE

    @Volatile
    private var geminiModel: GenerativeModel? = null

    @Volatile
    private var geminiInitializationError = ""

    @Volatile
    private var handsFreeEnabled = false

    private var recognitionInProgress = false
    private var waitingForResponse = false
    private var isTtsSpeaking = false
    private var pendingManualListen = false
    private var awaitingWakeCommand = false

    private var restartListeningRunnable: Runnable? = null
    private var responseTimeoutRunnable: Runnable? = null

    companion object {
        private const val MIC_PERMISSION = 101
        private const val PICK_MODEL_REQUEST = 1002
        private const val RESPONSE_TIMEOUT_MS = 60000L
    }

    // INICIALIZAÇÃO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App Check: provedor de depuração.
        // Registe o token apresentado nos registos do Firebase
        // na secção App Check do projeto.
        try {
            FirebaseAppCheck.getInstance()
                .installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )

            showStatus("Proteção Firebase preparada.")
        } catch (e: Exception) {
            geminiInitializationError =
                e.message ?: "Falha ao inicializar o App Check."
        }

        // Inicializar Gemini.
        try {
            geminiModel = Firebase.ai(
                backend = GenerativeBackend.googleAI()
            ).generativeModel("gemini-2.5-flash")

        } catch (e: Exception) {
            geminiModel = null
            geminiInitializationError =
                e.message ?: "Não foi possível inicializar o Gemini."
        }

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
                    val modelFile = File(
                        filesDir,
                        "model.litertlm"
                    )

                    if (
                        modelFile.exists() &&
                        modelFile.length() > 0L
                    ) {
                        showStatus("A preparar o Gemma local...")

                        if (!isModelLoaded && !isLoadingModel) {
                            backgroundExecutor.execute {
                                initializeLocalModel(modelFile)
                            }
                        }
                    } else {
                        showStatus(
                            "Toque no título J.A.R.V.I.S. para carregar o modelo."
                        )
                    }
                }
            }

            addJavascriptInterface(
                AndroidBridge(),
                "AndroidVoice"
            )
        }

        setContentView(webView)

        webView.loadUrl(
            "file:///android_asset/index.html"
        )
    }

    // TEXT TO SPEECH

    private fun initializeTts() {

        tts = TextToSpeech(this) { status ->

            mainHandler.post {

                if (status == TextToSpeech.SUCCESS) {

                    val languageResult = tts.setLanguage(
                        Locale("pt", "BR")
                    )

                    tts.setSpeechRate(0.96f)
                    tts.setPitch(0.92f)

                    tts.setOnUtteranceProgressListener(

                        object : UtteranceProgressListener() {

                            override fun onStart(
                                utteranceId: String?
                            ) {
                                mainHandler.post {

                                    if (
                                        utteranceId == "JARVIS_VOICE"
                                    ) {
                                        isTtsSpeaking = true
                                        stopRecognitionInternal()
                                        showStatus("A falar...")
                                    }
                                }
                            }

                            override fun onDone(
                                utteranceId: String?
                            ) {
                                mainHandler.post {

                                    if (
                                        utteranceId == "JARVIS_VOICE"
                                    ) {
                                        isTtsSpeaking = false

                                        showStatus(
                                            "Resposta concluída. Estou a ouvir..."
                                        )

                                        notifySpeechFinished()

                                        if (handsFreeEnabled) {
                                            scheduleListeningRestart(900)
                                        }
                                    }
                                }
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            override fun onError(
                                utteranceId: String?
                            ) {
                                mainHandler.post {

                                    if (
                                        utteranceId == "JARVIS_VOICE"
                                    ) {
                                        isTtsSpeaking = false

                                        showStatus(
                                            "Não foi possível concluir a fala."
                                        )

                                        notifySpeechFinished()

                                        if (handsFreeEnabled) {
                                            scheduleListeningRestart(900)
                                        }
                                    }
                                }
                            }

                            override fun onStop(
                                utteranceId: String?,
                                interrupted: Boolean
                            ) {
                                mainHandler.post {

                                    if (
                                        utteranceId == "JARVIS_VOICE"
                                    ) {
                                        isTtsSpeaking = false
                                        notifySpeechFinished()

                                        if (interrupted) {
                                            showStatus("Interrompido.")
                                        }

                                        if (
                                            handsFreeEnabled &&
                                            !interrupted
                                        ) {
                                            scheduleListeningRestart(900)
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

                    if (
                        ttsReady &&
                        pendingSpeech.isNotBlank()
                    ) {
                        val text = pendingSpeech
                        pendingSpeech = ""
                        speakText(text)
                    }

                } else {
                    ttsReady = false
                    showStatus(
                        "Não foi possível iniciar a voz Android."
                    )
                }
            }
        }
    }

    private fun notifySpeechFinished() {

        mainHandler.post {

            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeSpeechFinished === 'function') " +
                    "onNativeSpeechFinished();",
                null
            )
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
                    notifySpeechFinished()
                }

            } catch (_: Exception) {
                isTtsSpeaking = false
                showStatus("Erro ao reproduzir a voz.")
                notifySpeechFinished()
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
            clearResponseWait()

            if (handsFreeEnabled) {
                scheduleListeningRestart(900)
            }
        }
    }

    // MODO MÃOS-LIVRES AUTOMÁTICO

    private fun startHandsFree() {

        mainHandler.post {

            if (handsFreeEnabled) {
                showStatus("Modo mãos-livres já ativo.")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                showStatus("Reconhecimento de voz indisponível.")
                return@post
            }

            handsFreeEnabled = true
            waitingForResponse = false
            awaitingWakeCommand = false

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

            showStatus("J.A.R.V.I.S. pronto. Estou a ouvir...")
            scheduleListeningRestart(700)
        }
    }

    private fun stopHandsFree() {

        mainHandler.post {

            handsFreeEnabled = false
            waitingForResponse = false
            pendingManualListen = false
            awaitingWakeCommand = false

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
                    "A resposta está a demorar. Vou voltar a ouvir."
                )

                scheduleListeningRestart(1200)
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

    // RECONHECIMENTO DE VOZ

    private fun startListeningManual() {

        if (handsFreeEnabled) {
            showStatus(
                "O modo mãos-livres já controla o microfone."
            )
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

            if (handsFree && (waitingForResponse || isTtsSpeaking)) {
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
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)

                recognizer?.setRecognitionListener(
                    object : RecognitionListener {

                        override fun onReadyForSpeech(params: Bundle?) {
                            recognitionInProgress = true
                            showStatus("A ouvir...")
                        }

                        override fun onBeginningOfSpeech() {
                            showStatus("A ouvir...")
                        }

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
                                showStatus(
                                    "Reconhecimento interrompido. A tentar novamente..."
                                )

                                scheduleListeningRestart(
                                    if (
                                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                    ) 600 else 1200
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

                            if (text.isNullOrBlank()) {
                                if (handsFreeEnabled) {
                                    scheduleListeningRestart(600)
                                } else {
                                    showSpeechError(
                                        "Não foi reconhecida nenhuma frase."
                                    )
                                }
                                return
                            }

                            if (handsFreeEnabled) {
                                awaitingWakeCommand = false
                                beginResponseWait()
                                sendSpeechToInterface(text)
                                return
                            }

                            sendSpeechToInterface(text)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    }
                )

                val intent = Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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
                    showStatus(
                        "Não consegui iniciar o microfone. A tentar novamente..."
                    )
                    scheduleListeningRestart(1200)
                } else {
                    showSpeechError(
                        "Não foi possível iniciar o reconhecimento: ${e.message}"
                    )
                }
            }
        }
    }

    private fun sendSpeechToInterface(text: String) {

        mainHandler.post {

            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeSpeechResult === 'function') " +
                    "onNativeSpeechResult(${JSONObject.quote(text)});",
                null
            )
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
            scheduleListeningRestart(500)
        } else if (pendingManualListen) {
            pendingManualListen = false
            startListeningManual()
        }
    }

    // SELETOR E CÓPIA DO MODELO

    private fun openModelFileSelector() {

        mainHandler.post {

            val intent = Intent(
                Intent.ACTION_OPEN_DOCUMENT
            ).apply {
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

    // GEMMA LOCAL

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
                        Tu és J.A.R.V.I.S., um assistente virtual útil,
                        educado, inteligente e conciso.

                        Responde em português natural,
                        preferencialmente português do Brasil.

                        Responde diretamente ao que o utilizador pergunta.
                        Organiza respostas longas em tópicos quando necessário.

                        Dá prioridade a informações corretas.
                        Se não souberes alguma coisa, admite isso.
                        Não inventes notícias, horários, factos ou capacidades.

                        Não afirmes que executaste ações no dispositivo
                        se elas não tiverem sido realmente executadas.

                        Evita repetir a mesma informação sem necessidade.
                        Não comeces todas as respostas com a mesma frase.
                        """.trimIndent()
                    )
                )
            )

            modelEngine = newEngine
            modelConversation = newConversation

            isModelLoaded = true
            isLoadingModel = false

            mainHandler.post {

                if (!::webView.isInitialized) return@post

                webView.evaluateJavascript(
                    "if (typeof onNativeModelLoaded === 'function') " +
                        "onNativeModelLoaded(" +
                        "${JSONObject.quote("Gemma local pronto: $activeModelName")}" +
                        ");",
                    null
                )

                showStatus("Gemma local pronto.")
                startHandsFree()
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

    // GEMINI ONLINE

    private fun generateWithGemini(prompt: String): String? {

        val model = geminiModel ?: return null

        return try {
            runBlocking {
                model.generateContent(prompt).text
            }?.trim()?.takeIf { it.isNotBlank() }

        } catch (e: Exception) {
            geminiInitializationError =
                e.message ?: "O Gemini não conseguiu responder."
            null
        }
    }

    // BATERIA E LANTERNA

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

            if (enable) "Lanterna ligada." else "Lanterna desligada."

        } catch (e: Exception) {
            "Não foi possível controlar a lanterna: ${e.message}"
        }
    }

    // ABRIR APLICAÇÕES

    private fun openApplication(appNameInput: String): String {

        val appName = appNameInput
            .lowercase(Locale.ROOT)
            .trim()

        val packageName = when (appName) {
            "youtube" -> "com.google.android.youtube"
            "whatsapp" -> "com.whatsapp"
            "chrome", "google chrome" -> "com.android.chrome"
            "spotify" -> "com.spotify.music"
            "instagram" -> "com.instagram.android"
            "facebook" -> "com.facebook.katana"
            "telegram" -> "org.telegram.messenger"
            "gmail" -> "com.google.android.gm"
            "google maps", "maps" -> "com.google.android.apps.maps"
            else -> null
        }

        return try {
            when (appName) {
                "google" -> {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com")
                        )
                    )
                    return "A abrir o Google."
                }

                "youtube" -> {
                    val intent =
                        packageManager.getLaunchIntentForPackage(
                            "com.google.android.youtube"
                        )

                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com")
                            )
                        )
                    }

                    return "A abrir o YouTube."
                }

                "settings",
                "configurações",
                "configuracoes" -> {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                    return "A abrir as definições."
                }
            }

            if (packageName != null) {
                val intent =
                    packageManager.getLaunchIntentForPackage(packageName)

                if (intent != null) {
                    startActivity(intent)
                    return "A abrir $appName."
                }

                return "Não encontrei o aplicativo $appName instalado."
            }

            val intent =
                packageManager.getLaunchIntentForPackage(appNameInput)

            if (intent != null) {
                startActivity(intent)
                "A abrir $appName."
            } else {
                "Não encontrei o aplicativo $appName instalado."
            }

        } catch (_: Exception) {
            "Não foi possível abrir $appName."
        }
    }

    // LEMBRETES

    private fun handleReminderCommand(command: String): String? {

        val cmd = command.lowercase(Locale.ROOT).trim()

        val reminderPhrases = listOf(
            "cria um lembrete",
            "criar um lembrete",
            "cria lembrete",
            "criar lembrete",
            "adiciona um lembrete",
            "novo lembrete",
            "lembra-me",
            "lembra me",
            "me lembra",
            "lembrete para",
            "define um lembrete",
            "marca um lembrete"
        )

        if (reminderPhrases.none { cmd.contains(it) }) return null

        var title = command.trim()

        val prefixes = listOf(
            "cria um lembrete",
            "criar um lembrete",
            "cria lembrete",
            "criar lembrete",
            "adiciona um lembrete",
            "novo lembrete",
            "lembra-me de",
            "lembra me de",
            "lembra-me",
            "lembra me",
            "me lembra de",
            "me lembra",
            "define um lembrete",
            "marca um lembrete"
        )

        for (prefix in prefixes) {
            val index = title.indexOf(prefix, ignoreCase = true)

            if (index >= 0) {
                title = title.substring(index + prefix.length).trim()
                break
            }
        }

        title = title
            .replace(
                Regex(
                    """\bamanhã\b|\bamanha\b|\bhoje\b""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(
                Regex(
                    """\bàs?\s*\d{1,2}(?:\s*[:h]\s*\d{2}|\s+horas?)?\b""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', ',', '!', '?', ':')

        if (title.isBlank()) title = "Lembrete do JARVIS"

        val start = Calendar.getInstance()

        if (cmd.contains("amanhã") || cmd.contains("amanha")) {
            start.add(Calendar.DAY_OF_YEAR, 1)
        }

        val timeMatch = Regex(
            """\b([01]?\d|2[0-3])(?:\s*[:h]\s*([0-5]\d)|\s+horas?)\b"""
        ).find(cmd)

        val hasTime = timeMatch != null

        if (timeMatch != null) {
            val hour = timeMatch.groupValues[1].toInt()
            val minute = timeMatch.groupValues[2]
                .takeIf { it.isNotBlank() }
                ?.toInt() ?: 0

            start.set(Calendar.HOUR_OF_DAY, hour)
            start.set(Calendar.MINUTE, minute)
        } else {
            start.set(Calendar.HOUR_OF_DAY, 9)
            start.set(Calendar.MINUTE, 0)
        }

        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)

        val end = start.clone() as Calendar
        end.add(Calendar.MINUTE, 30)

        val calendarIntent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI

            putExtra(CalendarContract.Events.TITLE, title)

            putExtra(
                CalendarContract.Events.DESCRIPTION,
                "Criado pelo J.A.R.V.I.S. Android"
            )

            putExtra(
                CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                start.timeInMillis
            )

            putExtra(
                CalendarContract.EXTRA_EVENT_END_TIME,
                end.timeInMillis
            )

            putExtra(
                CalendarContract.Events.EVENT_TIMEZONE,
                TimeZone.getDefault().id
            )

            putExtra(CalendarContract.Events.ALL_DAY, false)
        }

        return try {
            startActivity(calendarIntent)

            val formattedTime = String.format(
                Locale.ROOT,
                "%02d:%02d",
                start.get(Calendar.HOUR_OF_DAY),
                start.get(Calendar.MINUTE)
            )

            if (hasTime) {
                "A abrir o calendário para criares o lembrete '$title' às $formattedTime. Confirma e guarda."
            } else {
                "A abrir o calendário para criares o lembrete '$title'. Sugeri as 09:00; confirma a data e a hora antes de guardar."
            }

        } catch (_: Exception) {
            "Não consegui abrir o calendário. Verifica se tens uma aplicação de calendário instalada."
        }
    }

    // COMANDOS ANDROID

    private fun handleExtraAndroidCommand(command: String): String? {

        val cmd = command.lowercase(Locale.ROOT).trim()

        handleReminderCommand(command)?.let {
            return it
        }

        if (
            cmd.contains("está a carregar") ||
            cmd.contains("esta a carregar") ||
            cmd.contains("estado do carregamento") ||
            cmd.contains("estado da carga")
        ) {
            val battery = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val status = battery?.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                -1
            ) ?: -1

            return when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING ->
                    "O telemóvel está a carregar."

                BatteryManager.BATTERY_STATUS_FULL ->
                    "A bateria está totalmente carregada."

                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                    "O telemóvel não está a carregar."

                else ->
                    "Não consegui verificar o estado do carregamento."
            }
        }

        if (cmd.contains("câmara") || cmd.contains("camera")) {
            return launchAndroidIntent(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                "A abrir a câmara.",
                "Não consegui abrir a câmara."
            )
        }

        if (cmd.contains("calculadora")) {
            val calculatorIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALCULATOR)
            }

            try {
                startActivity(calculatorIntent)
                return "A abrir a calculadora."
            } catch (_: Exception) {
            }

            val packages = listOf(
                "com.google.android.calculator",
                "com.android.calculator2",
                "com.sec.android.app.popupcalculator"
            )

            for (calculatorPackage in packages) {
                try {
                    val appIntent =
                        packageManager.getLaunchIntentForPackage(
                            calculatorPackage
                        )

                    if (appIntent != null) {
                        startActivity(appIntent)
                        return "A abrir a calculadora."
                    }
                } catch (_: Exception) {
                }
            }

            return "Não encontrei uma calculadora compatível."
        }

        if (
            cmd.contains("google maps") ||
            cmd.contains("mapas") ||
            cmd.contains("navegação") ||
            cmd.contains("navegacao")
        ) {
            return launchAndroidIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=destino")
                ),
                "A abrir os mapas.",
                "Não consegui abrir uma aplicação de mapas."
            )
        }

        if (cmd.contains("wi-fi") || cmd.contains("wifi")) {
            return launchAndroidIntent(
                Intent(Settings.ACTION_WIFI_SETTINGS),
                "A abrir as definições de Wi-Fi.",
                "Não consegui abrir as definições de Wi-Fi."
            )
        }

        if (cmd.contains("bluetooth")) {
            return launchAndroidIntent(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                "A abrir as definições de Bluetooth.",
                "Não consegui abrir as definições de Bluetooth."
            )
        }

        if (
            cmd.contains("aumenta o volume") ||
            cmd.contains("aumentar o volume") ||
            cmd.contains("volume mais alto")
        ) {
            changeMusicVolume(AudioManager.ADJUST_RAISE)
            return "A aumentar o volume."
        }

        if (
            cmd.contains("diminui o volume") ||
            cmd.contains("diminuir o volume") ||
            cmd.contains("abaixa o volume") ||
            cmd.contains("volume mais baixo")
        ) {
            changeMusicVolume(AudioManager.ADJUST_LOWER)
            return "A diminuir o volume."
        }

        if (
            cmd == "silenciar" ||
            cmd.contains("silencia o som") ||
            cmd.contains("silencia o volume") ||
            cmd.contains("silencia a música") ||
            cmd.contains("silencia a musica")
        ) {
            changeMusicVolume(AudioManager.ADJUST_MUTE)
            return "Comando de silêncio executado."
        }

        if (
            cmd.contains("reativa o som") ||
            cmd.contains("ativa o som") ||
            cmd.contains("tira o silêncio") ||
            cmd.contains("tira o silencio")
        ) {
            changeMusicVolume(AudioManager.ADJUST_UNMUTE)
            return "A reativar o som multimédia."
        }

        val alarmTime = Regex(
            """\b([01]?\d|2[0-3])[:h]([0-5]\d)\b"""
        ).find(cmd)

        if (cmd.contains("alarme") && alarmTime != null) {
            val hour = alarmTime.groupValues[1].toInt()
            val minute = alarmTime.groupValues[2].toInt()

            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Alarme do JARVIS")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }

            return launchAndroidIntent(
                alarmIntent,
                "A preparar um alarme para %02d:%02d.".format(
                    Locale.ROOT,
                    hour,
                    minute
                ),
                "Não consegui abrir a criação de alarmes."
            )
        }

        if (
            cmd.contains("alarme") ||
            cmd.contains("despertador") ||
            cmd.contains("abre o relógio") ||
            cmd.contains("abre o relogio")
        ) {
            return launchAndroidIntent(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                "A abrir os alarmes.",
                "Não consegui abrir os alarmes."
            )
        }

        val isOpenRequest =
            cmd.contains("abre ") ||
                cmd.startsWith("abrir ") ||
                cmd.startsWith("inicia ") ||
                cmd.startsWith("iniciar ")

        if (isOpenRequest) {
            val apps = listOf(
                "google maps",
                "whatsapp",
                "chrome",
                "google chrome",
                "spotify",
                "instagram",
                "facebook",
                "telegram",
                "gmail",
                "youtube",
                "google"
            )

            val requestedApp = apps.firstOrNull {
                cmd.contains(it)
            }

            if (requestedApp != null) {
                return openApplication(requestedApp)
            }
        }

        if (
            cmd.contains("abre contactos") ||
            cmd.contains("abrir contactos") ||
            cmd.contains("abre os contactos") ||
            cmd.contains("abre contatos") ||
            cmd.contains("abrir contatos") ||
            cmd.contains("abre os contatos")
        ) {
            return launchAndroidIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    ContactsContract.Contacts.CONTENT_URI
                ),
                "A abrir os contactos.",
                "Não consegui abrir os contactos."
            )
        }

        if (
            cmd.contains("abre o telefone") ||
            cmd.contains("abrir telefone") ||
            cmd.contains("abre o marcador") ||
            cmd.contains("abrir marcador") ||
            cmd.contains("abre chamadas")
        ) {
            return launchAndroidIntent(
                Intent(Intent.ACTION_DIAL),
                "A abrir o telefone.",
                "Não consegui abrir o telefone."
            )
        }

        if (
            cmd.contains("abre o calendário") ||
            cmd.contains("abre o calendario") ||
            cmd.contains("abrir calendário") ||
            cmd.contains("abrir calendario")
        ) {
            val calendarIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
            }

            return launchAndroidIntent(
                calendarIntent,
                "A abrir o calendário.",
                "Não encontrei uma aplicação de calendário disponível."
            )
        }

        if (
            cmd.contains("definições do ecrã") ||
            cmd.contains("definicoes do ecra") ||
            cmd.contains("configurações do ecrã") ||
            cmd.contains("configuracoes do ecra") ||
            cmd.contains("brilho do ecrã") ||
            cmd.contains("brilho do ecra")
        ) {
            return launchAndroidIntent(
                Intent(Settings.ACTION_DISPLAY_SETTINGS),
                "A abrir as definições do ecrã.",
                "Não consegui abrir as definições do ecrã."
            )
        }

        if (
            cmd.contains("definições de notificações") ||
            cmd.contains("definicoes de notificacoes") ||
            cmd.contains("configurações de notificações") ||
            cmd.contains("configuracoes de notificacoes")
        ) {
            return launchAndroidIntent(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_APP_PACKAGE,
                        this@MainActivity.packageName
                    )
                },
                "A abrir as definições de notificações do JARVIS.",
                "Não consegui abrir as definições de notificações."
            )
        }

        if (
            cmd.contains("definições da bateria") ||
            cmd.contains("definicoes da bateria") ||
            cmd.contains("configurações da bateria") ||
            cmd.contains("configuracoes da bateria") ||
            cmd.contains("economia de bateria")
        ) {
            return launchAndroidIntent(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                "A abrir as definições de poupança de bateria.",
                "Não consegui abrir essas definições."
            )
        }

        return null
    }

    private fun launchAndroidIntent(
        intent: Intent,
        successMessage: String,
        errorMessage: String
    ): String {
        return try {
            startActivity(intent)
            successMessage
        } catch (_: Exception) {
            errorMessage
        }
    }

    private fun changeMusicVolume(direction: Int) {
        try {
            val audioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager

            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
            showStatus("Não consegui alterar o volume.")
        }
    }

    // PROCESSAMENTO: GEMINI PRIMEIRO, GEMMA DEPOIS

    private fun processCommand(prompt: String) {

        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return

        val generation: Int

        synchronized(requestLock) {
            if (requestInProgress) {
                showStatus("Ainda estou a processar o pedido anterior.")
                return
            }

            requestInProgress = true
            requestGeneration++
            generation = requestGeneration
        }

        mainHandler.post {
            processCommandOnMainThread(cleanPrompt, generation)
        }
    }

    private fun processCommandOnMainThread(
        prompt: String,
        generation: Int
    ) {

        if (generation != requestGeneration) return

        val text = prompt.lowercase(Locale.ROOT).trim()

        try {
            val extraAnswer = handleExtraAndroidCommand(prompt)

            if (extraAnswer != null) {
                respond(extraAnswer, generation)
                return
            }

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
                    (text.contains("liga") || text.contains("acende")) -> {
                    toggleFlashlight(true)
                }

                text.contains("lanterna") &&
                    (text.contains("desliga") || text.contains("apaga")) -> {
                    toggleFlashlight(false)
                }

                text.contains("horas") -> {
                    "Agora são ${
                        SimpleDateFormat(
                            "HH:mm",
                            Locale("pt", "BR")
                        ).format(Date())
                    }."
                }

                text.contains("data de hoje") ||
                    text == "que dia é hoje" ||
                    text == "qual é a data" ||
                    text == "qual e a data" ||
                    text == "diz-me a data" ||
                    text == "diz a data" -> {
                    "Hoje é ${
                        SimpleDateFormat(
                            "dd 'de' MMMM 'de' yyyy",
                            Locale("pt", "BR")
                        ).format(Date())
                    }."
                }

                text == "abre youtube" ||
                    text == "abre o youtube" -> {
                    openApplication("youtube")
                }

                text == "abre google" ||
                    text == "abre o google" -> {
                    openApplication("google")
                }

                text == "abre as configurações" ||
                    text == "abre as configuracoes" -> {
                    openApplication("settings")
                }

                text.contains("piada") -> {
                    "Porque é que o computador foi ao médico? Porque tinha um vírus!"
                }

                else -> null
            }

            if (hardwareAnswer != null) {
                respond(hardwareAnswer, generation)
                return
            }

            showStatus("A consultar o Gemini...")

            backgroundExecutor.execute {

                try {
                    // PRIMEIRA OPÇÃO: GEMINI ONLINE
                    var response = generateWithGemini(prompt)

                    if (generation != requestGeneration) {
                        return@execute
                    }

                    if (!response.isNullOrBlank()) {
                        respond(
                            response,
                            generation
                        )
                        return@execute
                    }

                    // SEGUNDA OPÇÃO: GEMMA LOCAL
                    if (
                        !isModelLoaded ||
                        modelConversation == null
                    ) {
                        val errorMessage =
                            if (geminiInitializationError.isNotBlank()) {
                                "O Gemini não respondeu e o Gemma local ainda não está pronto. " +
                                    "Detalhe: $geminiInitializationError"
                            } else {
                                "O Gemini não respondeu e o Gemma local ainda não está pronto."
                            }

                        respond(errorMessage, generation)
                        return@execute
                    }

                    if (generation != requestGeneration) {
                        return@execute
                    }

                    showStatus("Gemini indisponível. A usar o Gemma local...")

                    val conversation = modelConversation
                        ?: throw IllegalStateException(
                            "A conversa do Gemma não está disponível."
                        )

                    response = conversation.sendMessage(prompt)
                        .toString()
                        .trim()

                    if (generation != requestGeneration) {
                        return@execute
                    }

                    if (response.isNullOrBlank()) {
                        throw IllegalStateException(
                            "O Gemma devolveu uma resposta vazia."
                        )
                    }

                    respond(response, generation)

                } catch (e: Exception) {

                    if (generation == requestGeneration) {
                        showModelError(
                            "Erro no Gemini e no Gemma: " +
                                (e.message ?: "erro desconhecido"),
                            generation
                        )
                    }
                }
            }

        } catch (e: Exception) {

            if (generation == requestGeneration) {
                showModelError(
                    "Erro ao processar o comando: " +
                        (e.message ?: "erro desconhecido"),
                    generation
                )
            }
        }
    }

    // RESPOSTAS E ESTADOS

    private fun respond(
        text: String,
        generation: Int? = null
    ) {
        val clean = text.trim()

        if (clean.isBlank()) {
            releaseRequest(generation)
            return
        }

        mainHandler.post {

            if (
                generation != null &&
                generation != requestGeneration
            ) {
                return@post
            }

            if (!::webView.isInitialized) return@post

            releaseRequest(generation)

            webView.evaluateJavascript(
                "if (typeof onNativeModelResponse === 'function') " +
                    "onNativeModelResponse(${JSONObject.quote(clean)});",
                null
            )

            showStatus("Resposta pronta.")
        }
    }

    private fun releaseRequest(generation: Int? = null) {

        synchronized(requestLock) {
            if (
                generation == null ||
                generation == requestGeneration
            ) {
                requestInProgress = false
            }
        }

        clearResponseWait()
    }

    // INTERRUPÇÃO TOTAL

    private fun interruptEverything() {

        mainHandler.post {

            synchronized(requestLock) {
                requestGeneration++
                requestInProgress = false
            }

            clearResponseWait()

            pendingManualListen = false
            awaitingWakeCommand = false
            handsFreeEnabled = false

            stopRecognitionInternal()

            try {
                if (::tts.isInitialized) {
                    tts.stop()
                }
            } catch (_: Exception) {
            }

            pendingSpeech = ""
            isTtsSpeaking = false

            notifyHandsFreeState(false, "Interrompido.")
            showStatus("Interrompido.")
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

    private fun showModelError(
        message: String,
        generation: Int? = null
    ) {

        mainHandler.post {

            if (
                generation != null &&
                generation != requestGeneration
            ) {
                return@post
            }

            releaseRequest(generation)

            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeModelError === 'function') " +
                    "onNativeModelError(${JSONObject.quote(message)});",
                null
            )

            if (handsFreeEnabled) {
                scheduleListeningRestart(1000)
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

    // PONTE ANDROID / JAVASCRIPT

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
        fun stopListening() {
            interruptEverything()
        }

        @JavascriptInterface
        fun stopEverything() {
            interruptEverything()
        }

        @JavascriptInterface
        fun cancelPendingRequest() {
            interruptEverything()
        }

        @JavascriptInterface
        fun openApp(appName: String) {
            mainHandler.post {
                val result = openApplication(appName)
                respond(result)
            }
        }
    }

    // DESTRUIR A APLICAÇÃO

    override fun onDestroy() {

        handsFreeEnabled = false
        waitingForResponse = false
        awaitingWakeCommand = false

        synchronized(requestLock) {
            requestGeneration++
            requestInProgress = false
        }

        cancelResponseTimeout()
        stopRecognitionInternal()

        try {
            if (::tts.isInitialized) {
                tts.stop()
                tts.shutdown()
            }
        } catch (_: Exception) {
        }

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
        geminiModel = null

        backgroundExecutor.shutdownNow()

        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidVoice")
            webView.destroy()
        }

        super.onDestroy()
    }
}
