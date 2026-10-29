
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

    companion object {
        private const val MIC_PERMISSION = 101
        private const val PICK_MODEL_REQUEST = 1002
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
    // TEXT-TO-SPEECH
    // O HTML controla quando uma resposta deve ser falada.
    // ---------------------------------------------------------

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS) {
                    val languageResult = tts.setLanguage(
                        Locale("pt", "BR")
                    )

                    tts.setSpeechRate(0.96f)
                    tts.setPitch(0.92f)

                    ttsReady =
                        languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                    showStatus(
                        if (ttsReady) {
                            "Voz Android pronta."
                        } else {
                            "Instala ou seleciona uma voz portuguesa nas definições do Android."
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
        if (clean.isBlank()) return

        mainHandler.post {
            if (!ttsReady) {
                pendingSpeech = clean
                return@post
            }

            try {
                tts.speak(
                    clean,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "JARVIS_VOICE"
                )
            } catch (_: Exception) {
                showStatus("Erro ao reproduzir a voz.")
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
            } catch (_: Exception) {
            }
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
                startActivityForResult(
                    intent,
                    PICK_MODEL_REQUEST
                )
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
    // INICIALIZAÇÃO DO LITERT-LM
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
    // RECONHECIMENTO DE VOZ ANDROID
    // ---------------------------------------------------------

    private fun startListeningManual() {
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

        mainHandler.post {
            try {
                recognizer?.cancel()
                recognizer?.destroy()

                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    showSpeechError(
                        "O reconhecimento de voz não está disponível neste dispositivo."
                    )
                    return@post
                }

                recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {

                        override fun onReadyForSpeech(params: Bundle?) {
                            showStatus("A ouvir...")
                        }

                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            showStatus("A processar a voz...")
                        }

                        override fun onError(error: Int) {
                            showSpeechError(
                                "Reconhecimento de voz terminado (código $error)."
                            )
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION
                                )
                                ?.firstOrNull()

                            if (!text.isNullOrBlank()) {
                                mainHandler.post {
                                    webView.evaluateJavascript(
                                        "if (typeof onNativeSpeechResult === 'function') " +
                                            "onNativeSpeechResult(" +
                                            "${JSONObject.quote(text)}" +
                                            ");",
                                        null
                                    )
                                }
                            } else {
                                showSpeechError(
                                    "Não foi reconhecida nenhuma frase."
                                )
                            }
                        }

                        override fun onPartialResults(
                            partialResults: Bundle?
                        ) {}

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?
                        ) {}
                    })

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
                    }

                    startListening(intent)
                }
            } catch (e: Exception) {
                showSpeechError(
                    "Não foi possível iniciar o reconhecimento: ${e.message}"
                )
            }
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

            startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            "A abrir $appName."
        } catch (_: Exception) {
            "Não foi possível abrir $appName."
        }
    }

    private fun processCommand(prompt: String) {
        val text = prompt.lowercase(Locale.ROOT).trim()

        // Comandos executados diretamente no Android.
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

                // CORREÇÃO: Message não possui a propriedade .text.
                // toString() devolve o conteúdo da mensagem.
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
    // COMUNICAÇÃO COM O HTML
    // O JavaScript apresenta a resposta e controla a voz.
    // ---------------------------------------------------------

    private fun respond(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return

        val now = System.currentTimeMillis()

        if (
            clean == lastSpokenText &&
            now - lastSpokenTime < 1500
        ) {
            return
        }

        lastSpokenText = clean
        lastSpokenTime = now

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
        mainHandler.post {
            if (!::webView.isInitialized) return@post

            webView.evaluateJavascript(
                "if (typeof onNativeModelError === 'function') " +
                    "onNativeModelError(${JSONObject.quote(message)});",
                null
            )
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
        fun chooseModel() {
            openModelFileSelector()
        }

        @JavascriptInterface
        fun speak(text: String) {
            speakText(text)
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
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        stopSpeaking()

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
