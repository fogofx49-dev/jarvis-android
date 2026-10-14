
package com.fogofx.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var ttsReady = false

    private var modelUri: Uri? = null
    private var modelFile: File? = null
    private var modelEngine: Engine? = null
    private var modelConversation: Conversation? = null

    @Volatile
    private var modelReady = false

    @Volatile
    private var modelLoading = false

    private val aiExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val MIC_PERMISSION = 101
        private const val MODEL_PICKER = 102

        // Preenche apenas se quiseres selecionar uma voz específica.
        private const val PREFERRED_VOICE_ID = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            configureJarvisVoice(status)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)
                    initializeGemma()
                }
            }

            webChromeClient = WebChromeClient()

            addJavascriptInterface(
                AndroidBridge(),
                "AndroidVoice"
            )
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    // Configura a voz do JARVIS em português.
    private fun configureJarvisVoice(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            showStatus("Não foi possível iniciar a voz do JARVIS.")
            return
        }

        try {
            val localeBR = Locale("pt", "BR")
            val languageResult = tts.setLanguage(localeBR)

            if (
                languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ttsReady = false
                showStatus(
                    "Português do Brasil indisponível. " +
                    "Verifica as definições de voz do Android."
                )
                return
            }

            val availableVoices = tts.voices
                ?.filter {
                    it.locale.language == "pt" &&
                    it.locale.country == "BR"
                }
                .orEmpty()

            availableVoices.forEach { voice ->
                android.util.Log.d(
                    "JARVIS_VOICES",
                    "ID=${voice.name}, " +
                    "Idioma=${voice.locale}, " +
                    "Rede=${voice.isNetworkConnectionRequired}, " +
                    "Qualidade=${voice.quality}, " +
                    "Latência=${voice.latency}"
                )
            }

            val selectedVoice =
                if (PREFERRED_VOICE_ID.isNotBlank()) {
                    availableVoices.firstOrNull {
                        it.name == PREFERRED_VOICE_ID
                    }
                } else {
                    null
                }

            if (selectedVoice != null) {
                tts.voice = selectedVoice
            }

            tts.setSpeechRate(0.94f)
            tts.setPitch(0.92f)

            ttsReady = true

            showStatus(
                if (selectedVoice != null) {
                    "Voz selecionada: ${selectedVoice.name}"
                } else {
                    "Voz padrão pronta • Português BR"
                }
            )

            android.util.Log.d(
                "JARVIS_VOICES",
                "Vozes pt-BR: " +
                    availableVoices.joinToString(", ") {
                        it.name
                    }
            )

        } catch (e: Exception) {
            ttsReady = false

            showStatus("Erro ao configurar a voz do JARVIS.")

            android.util.Log.e(
                "JARVIS_VOICES",
                "Erro ao configurar voz",
                e
            )
        }
    }

    // Funções chamadas pelo JavaScript da interface HTML.
    inner class AndroidBridge {

        @JavascriptInterface
        fun listVoices() {
            runOnUiThread {
                try {
                    if (!::tts.isInitialized || !ttsReady) {
                        showStatus("A voz do Android ainda está a iniciar.")
                        return@runOnUiThread
                    }

                    val voices = tts.voices
                        ?.filter {
                            it.locale.language == "pt"
                        }
                        .orEmpty()

                    if (voices.isEmpty()) {
                        showStatus(
                            "Nenhuma voz portuguesa encontrada."
                        )
                        return@runOnUiThread
                    }

                    val json = JSONArray()

                    voices.forEach { voice ->
                        val item = JSONObject().apply {
                            put("name", voice.name)
                            put("id", voice.name)
                            put("locale", voice.locale.toString())
                            put("quality", voice.quality)
                            put("latency", voice.latency)
                            put(
                                "network",
                                voice.isNetworkConnectionRequired
                            )
                            put(
                                "features",
                                JSONArray(voice.features ?: emptySet<String>())
                            )
                        }

                        json.put(item)
                    }

                    val report = voices.joinToString("\n") {
                        "ID=${it.name} | " +
                        "Idioma=${it.locale} | " +
                        "Qualidade=${it.quality} | " +
                        "Latência=${it.latency} | " +
                        "Rede=${it.isNetworkConnectionRequired}"
                    }

                    android.util.Log.d(
                        "JARVIS_VOICES",
                        "LISTA COMPLETA:\n$report"
                    )

                    webView.evaluateJavascript(
                        "if (typeof onNativeVoicesListed === 'function') " +
                        "onNativeVoicesListed(" +
                        "${JSONObject.quote(json.toString())});",
                        null
                    )

                    showStatus(
                        "${voices.size} vozes portuguesas encontradas."
                    )

                } catch (e: Exception) {
                    showStatus(
                        "Erro ao consultar as vozes disponíveis."
                    )

                    android.util.Log.e(
                        "JARVIS_VOICES",
                        "Erro ao listar vozes",
                        e
                    )
                }
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                if (
                    ttsReady &&
                    text.isNotBlank() &&
                    !isFinishing
                ) {
                    tts.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "jarvis-voice"
                    )
                }
            }
        }

        @JavascriptInterface
        fun openApp(app: String) {
            runOnUiThread {
                try {
                    val intent = when (app.lowercase(Locale.ROOT)) {
                        "youtube" -> Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://m.youtube.com/")
                        )

                        "google" -> Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/")
                        )

                        "settings" -> Intent(
                            android.provider.Settings.ACTION_SETTINGS
                        )

                        else -> null
                    }

                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        showStatus("Comando não reconhecido.")
                    }

                } catch (e: Exception) {
                    showStatus(
                        "Não consegui abrir essa aplicação."
                    )
                }
            }
        }

        @JavascriptInterface
        fun startListening() {
            runOnUiThread {
                if (
                    !SpeechRecognizer.isRecognitionAvailable(
                        this@MainActivity
                    )
                ) {
                    showStatus(
                        "Não há serviço de reconhecimento de voz disponível."
                    )
                    return@runOnUiThread
                }

                if (
                    checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        MIC_PERMISSION
                    )
                    return@runOnUiThread
                }

                beginRecognition()
            }
        }

        // Abre o seletor de ficheiros do Android.
        @JavascriptInterface
        fun chooseModel() {
            runOnUiThread {
                openModelPicker()
            }
        }

        // Envia a pergunta para o modelo local.
        @JavascriptInterface
        fun askModel(prompt: String) {
            val question = prompt.trim()

            if (question.isBlank()) return

            if (!modelReady) {
                showModelResponse(
                    "O Gemma ainda não está pronto. " +
                    "Seleciona o ficheiro .litertlm e aguarda a inicialização."
                )
                return
            }

            try {
                aiExecutor.execute {
                    try {
                        val conversation = modelConversation
                            ?: throw IllegalStateException(
                                "Conversa com o Gemma indisponível."
                            )

                        val result = conversation.sendMessage(
                            question,
                            maxOutputToken = 256
                        )

                        val answer = result.toString().trim()

                        showModelResponse(
                            answer.ifBlank {
                                "Não consegui gerar uma resposta."
                            }
                        )

                    } catch (e: Exception) {
                        android.util.Log.e(
                            "JARVIS_MODEL",
                            "Erro ao gerar resposta",
                            e
                        )

                        showModelResponse(
                            "Ocorreu um erro ao gerar a resposta do Gemma."
                        )
                    }
                }
            } catch (e: Exception) {
                showModelResponse(
                    "Não foi possível iniciar a tarefa de IA."
                )
            }
        }
    }

    // Devolve a resposta do modelo à interface HTML.
    private fun showModelResponse(answer: String) {
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onNativeModelResponse === 'function') " +
                    "onNativeModelResponse(" +
                    "${JSONObject.quote(answer)});",
                    null
                )
            }
        }
    }

    // Atualiza o estado apresentado na interface HTML.
    private fun showStatus(message: String) {
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onNativeStatus === 'function') " +
                    "onNativeStatus(${JSONObject.quote(message)});",
                    null
                )
            }
        }
    }

    // Inicia o reconhecimento de voz do Android.
    private fun beginRecognition() {
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        showStatus("Estou a ouvir…")
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        showStatus("A processar a voz…")
                    }

                    override fun onError(error: Int) {
                        showStatus(
                            "Não consegui reconhecer a voz. Tenta novamente."
                        )
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val text = matches?.firstOrNull().orEmpty()

                        if (text.isNotBlank()) {
                            runOnUiThread {
                                if (
                                    !isFinishing &&
                                    ::webView.isInitialized
                                ) {
                                    webView.evaluateJavascript(
                                        "if (typeof onNativeSpeechResult === 'function') " +
                                        "onNativeSpeechResult(" +
                                        "${JSONObject.quote(text)});",
                                        null
                                    )
                                }
                            }
                        } else {
                            showStatus(
                                "Não ouvi uma frase. Tenta novamente."
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
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {
            android.util.Log.e(
                "JARVIS_VOICE",
                "Erro ao iniciar reconhecimento",
                e
            )

            showStatus("Não foi possível iniciar o microfone.")
        }
    }

    // Abre o gestor de ficheiros para escolher o modelo.
    private fun openModelPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }

            startActivityForResult(intent, MODEL_PICKER)

        } catch (e: Exception) {
            showStatus(
                "Não foi possível abrir o seletor de ficheiros."
            )
        }
    }

    // Inicializa o modelo guardado no armazenamento privado da aplicação.
    private fun initializeGemma() {
        val file = File(filesDir, "model.litertlm")

        if (!file.exists() || file.length() == 0L) {
            showStatus(
                "Seleciona o ficheiro do modelo .litertlm."
            )
            return
        }

        if (modelReady || modelLoading) return

        modelLoading = true
        showStatus("A iniciar o Gemma...")

        try {
            aiExecutor.execute {
                var newEngine: Engine? = null

                try {
                    val config = EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU()
                    )

                    newEngine = Engine(config)
                    newEngine.initialize()

                    val conversation = newEngine.createConversation()

                    modelEngine = newEngine
                    modelConversation = conversation
                    modelFile = file
                    modelReady = true

                    showStatus("Gemma iniciado com sucesso!")

                } catch (e: Exception) {
                    try {
                        newEngine?.close()
                    } catch (_: Exception) {
                    }

                    modelEngine = null
                    modelConversation = null
                    modelReady = false

                    android.util.Log.e(
                        "JARVIS_MODEL",
                        "Erro ao iniciar o Gemma",
                        e
                    )

                    showStatus(
                        "Erro ao iniciar o Gemma: " +
                        (e.message ?: "erro desconhecido")
                    )

                } finally {
                    modelLoading = false
                }
            }
        } catch (e: Exception) {
            modelLoading = false
            showStatus("Não foi possível iniciar a tarefa de IA.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode != MODEL_PICKER ||
            resultCode != RESULT_OK
        ) {
            return
        }

        val uri = data?.data ?: return
        modelUri = uri

        showStatus("A copiar o modelo para o JARVIS...")

        Thread {
            try {
                val destination = File(
                    filesDir,
                    "model.litertlm"
                )

                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException(
                        "Não foi possível abrir o ficheiro selecionado."
                    )

                input.use { source ->
                    FileOutputStream(destination).use { output ->
                        source.copyTo(output)
                    }
                }

                if (
                    !destination.exists() ||
                    destination.length() == 0L
                ) {
                    throw IOException(
                        "O ficheiro copiado está vazio."
                    )
                }

                modelFile = destination

                runOnUiThread {
                    showStatus(
                        "Modelo copiado! A iniciar o Gemma..."
                    )
                    initializeGemma()
                }

            } catch (e: Exception) {
                android.util.Log.e(
                    "JARVIS_MODEL",
                    "Erro ao copiar o modelo",
                    e
                )

                showStatus(
                    "Erro ao copiar: " +
                    (e.message ?: e.javaClass.simpleName)
                )
            }
        }.start()
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

        if (requestCode == MIC_PERMISSION) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                beginRecognition()
            } else {
                showStatus(
                    "A permissão do microfone foi recusada."
                )
            }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        // Impede que novas tarefas sejam aceites.
        aiExecutor.shutdownNow()

        try {
            modelConversation?.close()
        } catch (e: Exception) {
            android.util.Log.e(
                "JARVIS_MODEL",
                "Erro ao fechar a conversa",
                e
            )
        }

        try {
            modelEngine?.close()
        } catch (e: Exception) {
            android.util.Log.e(
                "JARVIS_MODEL",
                "Erro ao fechar o motor",
                e
            )
        }

        modelConversation = null
        modelEngine = null
        modelReady = false

        super.onDestroy()
    }
}




