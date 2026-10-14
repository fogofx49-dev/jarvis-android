  
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

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var ttsReady = false

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

    private val aiExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val MIC_PERMISSION = 101
        private const val MODEL_PICKER = 102

        private const val MODEL_FILENAME = "model.litertlm"

        // Deixa vazio para usar a voz padrão do Android.
        private const val PREFERRED_VOICE_ID = ""

        private const val SYSTEM_INSTRUCTION = """
És o JARVIS, um assistente de inteligência artificial útil,
educado, rigoroso e objetivo.

REGRAS OBRIGATÓRIAS:
1. Responde sempre em português do Brasil.
2. Responde à pergunta que o utilizador realmente fez.
3. Não mudes de assunto nem respondas com informação irrelevante.
4. Não inventes factos, fontes, datas, estatísticas ou acontecimentos.
5. Se não souberes uma resposta ou não tiveres informação suficiente,
   diz isso claramente e pede esclarecimentos quando necessário.
6. Se a pergunta for ambígua, explica brevemente o que precisas de saber.
7. Usa linguagem natural, frases claras e parágrafos curtos.
8. Para perguntas simples, responde de forma breve e direta.
9. Para perguntas complexas, organiza a resposta em passos.
10. Não afirmes que consultaste a Internet ou informações em tempo real
    quando não o fizeste.
11. Não repitas a pergunta sem necessidade.
12. Não inventes capacidades, ações ou resultados que não executaste.
13. Mantém o contexto da conversa e interpreta a mensagem mais recente
    tendo em conta as mensagens anteriores.
14. Não apresentes uma suposição como se fosse um facto confirmado.

O teu objetivo é ajudar corretamente. A precisão é mais importante
do que dar uma resposta inventada.
"""
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

    // ---------------------------------------------------------
    // VOZ DO JARVIS
    // ---------------------------------------------------------

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
                    "ID=${voice.name}, idioma=${voice.locale}, " +
                    "qualidade=${voice.quality}, latência=${voice.latency}"
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

        } catch (e: Exception) {
            ttsReady = false

            android.util.Log.e(
                "JARVIS_VOICES",
                "Erro ao configurar voz",
                e
            )

            showStatus("Erro ao configurar a voz do JARVIS.")
        }
    }

    // ---------------------------------------------------------
    // INTERFACE JAVASCRIPT / HTML
    // ---------------------------------------------------------

    inner class AndroidBridge {

        @JavascriptInterface
        fun listVoices() {
            runOnUiThread {
                try {
                    if (!ttsReady) {
                        showStatus("A voz do Android ainda está a iniciar.")
                        return@runOnUiThread
                    }

                    val voices = tts.voices
                        ?.filter { it.locale.language == "pt" }
                        .orEmpty()

                    val json = JSONArray()

                    voices.forEach { voice ->
                        json.put(
                            JSONObject().apply {
                                put("name", voice.name)
                                put("id", voice.name)
                                put("locale", voice.locale.toString())
                                put("quality", voice.quality)
                                put("latency", voice.latency)
                                put(
                                    "network",
                                    voice.isNetworkConnectionRequired
                                )
                            }
                        )
                    }

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
                    android.util.Log.e(
                        "JARVIS_VOICES",
                        "Erro ao listar vozes",
                        e
                    )
                    showStatus("Erro ao consultar as vozes disponíveis.")
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
        fun stopSpeaking() {
            runOnUiThread {
                if (::tts.isInitialized) {
                    tts.stop()
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
                    android.util.Log.e(
                        "JARVIS_APP",
                        "Erro ao abrir aplicação",
                        e
                    )
                    showStatus("Não consegui abrir essa aplicação.")
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
                    notifySpeechError(
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

        @JavascriptInterface
        fun chooseModel() {
            runOnUiThread {
                openModelPicker()
            }
        }

        // Recebe a pergunta enviada pelo HTML.
        @JavascriptInterface
        fun askModel(prompt: String) {
            val question = prompt.trim()

            if (question.isBlank()) {
                showModelError("Escreve uma pergunta antes de enviar.")
                return
            }

            if (!modelReady) {
                showModelError(
                    "O Gemma ainda não está pronto. " +
                    "Carrega o ficheiro .litertlm e aguarda a inicialização."
                )
                return
            }

            try {
                aiExecutor.execute {
                    try {
                        val conversation = modelConversation
                            ?: throw IllegalStateException(
                                "A conversa com o Gemma não está disponível."
                            )

                        android.util.Log.d(
                            "JARVIS_MODEL",
                            "Pergunta recebida: $question"
                        )

                        val result = conversation.sendMessage(
                            question,
                            maxOutputToken = 512
                        )

                        // A API devolve um Message.
                        // Usa text para obter o texto da resposta.
                        val answer = result.text.trim()

                        android.util.Log.d(
                            "JARVIS_MODEL",
                            "Resposta recebida (${answer.length} caracteres)"
                        )

                        showModelResponse(
                            answer.ifBlank {
                                "Não consegui produzir uma resposta útil. " +
                                "Tenta reformular a pergunta."
                            }
                        )

                    } catch (e: Exception) {
                        android.util.Log.e(
                            "JARVIS_MODEL",
                            "Erro ao gerar resposta",
                            e
                        )

                        showModelError(
                            "O Gemma encontrou um erro ao responder. " +
                            "Verifica o Logcat para ver os detalhes."
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "JARVIS_MODEL",
                    "Não foi possível colocar a pergunta na fila",
                    e
                )

                showModelError(
                    "Não foi possível iniciar a tarefa de IA."
                )
            }
        }
    }

    // ---------------------------------------------------------
    // RESPOSTAS E ESTADO DA INTERFACE
    // ---------------------------------------------------------

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

    private fun showModelError(message: String) {
        android.util.Log.e("JARVIS_MODEL", message)

        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onNativeModelError === 'function') " +
                    "onNativeModelError(" +
                    "${JSONObject.quote(message)});",
                    null
                )
            }
        }
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onNativeStatus === 'function') " +
                    "onNativeStatus(" +
                    "${JSONObject.quote(message)});",
                    null
                )
            }
        }
    }

    private fun notifySpeechError(message: String) {
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onNativeSpeechError === 'function') " +
                    "onNativeSpeechError(" +
                    "${JSONObject.quote(message)});",
                    null
                )
            }
        }
    }

    // ---------------------------------------------------------
    // RECONHECIMENTO DE VOZ
    // ---------------------------------------------------------

    private fun beginRecognition() {
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        showStatus("Estou a ouvir...")
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        showStatus("A processar a voz...")
                    }

                    override fun onError(error: Int) {
                        android.util.Log.w(
                            "JARVIS_VOICE",
                            "Erro de reconhecimento: $error"
                        )

                        notifySpeechError(
                            when (error) {
                                SpeechRecognizer.ERROR_AUDIO ->
                                    "Erro de áudio. Verifica o microfone."

                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    "O JARVIS precisa da permissão do microfone."

                                SpeechRecognizer.ERROR_NETWORK,
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                    "O serviço de reconhecimento de voz teve um erro de rede."

                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    "Não reconheci a frase. Tenta novamente."

                                else ->
                                    "O reconhecimento de voz falhou. Tenta novamente."
                            }
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
                            notifySpeechError(
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

            notifySpeechError(
                "Não foi possível iniciar o microfone."
            )
        }
    }

    // ---------------------------------------------------------
    // SELEÇÃO E CÓPIA DO MODELO
    // ---------------------------------------------------------

    private fun openModelPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }

            startActivityForResult(intent, MODEL_PICKER)

        } catch (e: Exception) {
            android.util.Log.e(
                "JARVIS_MODEL",
                "Erro ao abrir seletor",
                e
            )

            showStatus(
                "Não foi possível abrir o seletor de ficheiros."
            )
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
            showStatus("Seleção de modelo cancelada.")
            return
        }

        val uri = data?.data ?: run {
            showStatus("Não foi selecionado nenhum ficheiro.")
            return
        }

        modelUri = uri
        showStatus("A copiar o modelo para o JARVIS...")

        Thread {
            val temporaryFile = File(
                filesDir,
                "$MODEL_FILENAME.tmp"
            )

            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException(
                        "Não foi possível abrir o ficheiro selecionado."
                    )

                input.use { source ->
                    FileOutputStream(temporaryFile).use { output ->
                        source.copyTo(output)
                    }
                }

                if (
                    !temporaryFile.exists() ||
                    temporaryFile.length() == 0L
                ) {
                    throw IOException(
                        "O ficheiro copiado está vazio."
                    )
                }

                // A fila serializa o encerramento do modelo antigo
                // e a inicialização do novo.
                aiExecutor.execute {
                    modelLoading = true
                    modelReady = false

                    try {
                        closeCurrentModel()

                        val destination = File(
                            filesDir,
                            MODEL_FILENAME
                        )

                        if (destination.exists() && !destination.delete()) {
                            throw IOException(
                                "Não foi possível substituir o modelo antigo."
                            )
                        }

                        if (!temporaryFile.renameTo(destination)) {
                            throw IOException(
                                "Não foi possível guardar o novo modelo."
                            )
                        }

                        modelFile = destination

                        initializeGemmaOnExecutor(destination)

                    } catch (e: Exception) {
                        android.util.Log.e(
                            "JARVIS_MODEL",
                            "Erro ao instalar o modelo",
                            e
                        )

                        modelReady = false
                        showStatus(
                            "Erro ao preparar o modelo: " +
                            (e.message ?: e.javaClass.simpleName)
                        )
                    } finally {
                        modelLoading = false
                    }
                }

            } catch (e: Exception) {
                temporaryFile.delete()

                android.util.Log.e(
                    "JARVIS_MODEL",
                    "Erro ao copiar o modelo",
                    e
                )

                showStatus(
                    "Erro ao copiar o modelo: " +
                    (e.message ?: e.javaClass.simpleName)
                )
            }
        }.start()
    }

    // ---------------------------------------------------------
    // INICIALIZAÇÃO DO GEMMA
    // ---------------------------------------------------------

    private fun initializeGemma() {
        val file = File(filesDir, MODEL_FILENAME)

        if (!file.exists() || file.length() == 0L) {
            showStatus(
                "Modelo não encontrado. Carrega um ficheiro .litertlm."
            )
            return
        }

        if (modelReady) {
            showStatus("O Gemma já está pronto.")
            return
        }

        if (modelLoading) {
            showStatus("O Gemma está a carregar...")
            return
        }

        modelLoading = true
        showStatus("A iniciar o Gemma...")

        try {
            aiExecutor.execute {
                try {
                    initializeGemmaOnExecutor(file)
                } catch (e: Exception) {
                    android.util.Log.e(
                        "JARVIS_MODEL",
                        "Falha ao inicializar o Gemma",
                        e
                    )

                    modelReady = false

                    showStatus(
                        "Erro ao iniciar o Gemma: " +
                        (e.message ?: e.javaClass.simpleName)
                    )
                } finally {
                    modelLoading = false
                }
            }
        } catch (e: Exception) {
            modelLoading = false

            android.util.Log.e(
                "JARVIS_MODEL",
                "Não foi possível agendar a inicialização",
                e
            )

            showStatus("Não foi possível iniciar a tarefa do Gemma.")
        }
    }

    // Executado apenas dentro de aiExecutor.
    private fun initializeGemmaOnExecutor(file: File) {
        var newEngine: Engine? = null
        var newConversation: Conversation? = null

        try {
            if (!file.exists() || file.length() == 0L) {
                throw IOException(
                    "O ficheiro do modelo não existe ou está vazio."
                )
            }

            android.util.Log.i(
                "JARVIS_MODEL",
                "A carregar ${file.absolutePath}; " +
                "tamanho=${file.length()} bytes"
            )

            showStatus("A carregar o motor de IA...")

            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU()
            )

            newEngine = Engine(config)
            newEngine.initialize()

            showStatus("A preparar a conversa com o Gemma...")

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION)
            )

            newConversation = newEngine.createConversation(
                conversationConfig
            )

            modelEngine = newEngine
            modelConversation = newConversation
            modelFile = file
            modelReady = true

            // A propriedade do motor e da conversa passa
            // para os campos da Activity.
            newEngine = null
            newConversation = null

            android.util.Log.i(
                "JARVIS_MODEL",
                "Gemma iniciado com sucesso."
            )

            showStatus(
                "Gemma iniciado com sucesso! Já podes conversar com a IA."
            )

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
            modelConversation = null
            modelEngine = null

            android.util.Log.e(
                "JARVIS_MODEL",
                "Erro de inicialização",
                e
            )

            throw e
        }
    }

    // Executado apenas dentro de aiExecutor.
    private fun closeCurrentModel() {
        modelReady = false

        try {
            modelConversation?.close()
        } catch (e: Exception) {
            android.util.Log.w(
                "JARVIS_MODEL",
                "Erro ao fechar a conversa anterior",
                e
            )
        }

        modelConversation = null

        try {
            modelEngine?.close()
        } catch (e: Exception) {
            android.util.Log.w(
                "JARVIS_MODEL",
                "Erro ao fechar o motor anterior",
                e
            )
        }

        modelEngine = null
    }

    // ---------------------------------------------------------
    // PERMISSÕES
    // ---------------------------------------------------------

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
                notifySpeechError(
                    "A permissão do microfone foi recusada."
                )
            }
        }
    }

    // ---------------------------------------------------------
    // ENCERRAMENTO
    // ---------------------------------------------------------

    override fun onDestroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        // Evita aceitar novas tarefas depois do encerramento.
        aiExecutor.shutdown()

        // Fecha o modelo na mesma fila que executa as inferências,
        // para evitar fechar a conversa durante uma resposta.
        try {
            aiExecutor.execute {
                closeCurrentModel()
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "JARVIS_MODEL",
                "A fila de IA já está encerrada.",
                e
            )
        }

        super.onDestroy()
    }
}
    
