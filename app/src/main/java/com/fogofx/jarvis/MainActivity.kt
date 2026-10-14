
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
        private const val TAG = "JARVIS"
        private const val MODEL_TAG = "JARVIS_MODEL"

        private const val MIC_PERMISSION = 101
        private const val MODEL_PICKER = 102

        private const val MODEL_FILENAME = "model.litertlm"

        private const val PREFERRED_VOICE_ID = ""

        private const val SYSTEM_INSTRUCTION = """
            Você é J.A.R.V.I.S., um assistente virtual pessoal.
            Responda em português do Brasil, de forma natural,
            educada, clara e útil.
            Seja objetivo, mas explique os passos quando necessário.
            Não afirme que executou ações que não executou.
            Quando não souber uma resposta, seja transparente.
        """
    }

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
                initializeGemma()
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

    // ---------------------------------------------------------
    // VOZ DO JARVIS
    // ---------------------------------------------------------

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureJarvisVoice()
            } else {
                ttsReady = false
                showStatus("Não foi possível iniciar a voz.")
            }
        }
    }

    private fun configureJarvisVoice() {
        val result = tts.setLanguage(
            Locale("pt", "BR")
        )

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
            voice.locale.language.equals(
                "pt",
                ignoreCase = true
            ) &&
                voice.locale.country.equals(
                    "BR",
                    ignoreCase = true
                )
        }

        Log.d(
            TAG,
            "Vozes pt-BR encontradas: ${brazilianVoices.size}"
        )

        for (voice in brazilianVoices) {
            Log.d(
                TAG,
                "Voz: ${voice.name}, qualidade=${voice.quality}"
            )
        }

        if (PREFERRED_VOICE_ID.isNotBlank()) {
            val preferredVoice = voices.find {
                it.name == PREFERRED_VOICE_ID
            }

            if (preferredVoice != null) {
                tts.voice = preferredVoice
            }
        } else if (brazilianVoices.isNotEmpty()) {
            val selectedVoice = brazilianVoices.maxByOrNull {
                it.quality
            }

            if (selectedVoice != null) {
                tts.voice = selectedVoice
            }
        }

        tts.setSpeechRate(0.94f)
        tts.setPitch(0.92f)

        ttsReady = true
        showStatus("Voz do JARVIS pronta.")
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            showStatus("A voz ainda não está pronta.")
            return
        }

        val cleanText = text.trim()

        if (cleanText.isBlank()) return

        runOnUiThread {
            tts.speak(
                cleanText,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "JARVIS_UTTERANCE"
            )
        }
    }

    private fun stopSpeaking() {
        if (::tts.isInitialized) {
            runOnUiThread {
                tts.stop()
            }
        }
    }

    private fun listVoices(): String {
        if (!::tts.isInitialized) {
            return "[]"
        }

        val result = JSONArray()
        val voices = tts.voices ?: emptySet<Voice>()

        for (voice in voices) {
            val item = JSONObject()

            item.put("name", voice.name)
            item.put(
                "language",
                voice.locale.toLanguageTag()
            )
            item.put("quality", voice.quality)
            item.put("latency", voice.latency)

            result.put(item)
        }

        return result.toString()
    }

    // ---------------------------------------------------------
    // PONTE ENTRE JAVASCRIPT E ANDROID
    // ---------------------------------------------------------

    inner class AndroidBridge {

        @JavascriptInterface
        fun listVoices() {
            val voicesJson = listVoices()

            runOnUiThread {
                webView.evaluateJavascript(
                    "if (window.onNativeVoicesListed) " +
                        "window.onNativeVoicesListed(" +
                        "${JSONObject.quote(voicesJson)});",
                    null
                )
            }
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
                checkMicrophonePermission()
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
    }

    // ---------------------------------------------------------
    // ABRIR APLICAÇÕES
    // ---------------------------------------------------------

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
                    android.provider.Settings.ACTION_SETTINGS
                )

                else -> {
                    showStatus(
                        "Não reconheço essa aplicação: $app"
                    )
                    return
                }
            }

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            startActivity(intent)
            showStatus("A abrir $app.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir aplicação.", e)
            showStatus("Não consegui abrir $app.")
        }
    }

    // ---------------------------------------------------------
    // RECONHECIMENTO DE VOZ
    // ---------------------------------------------------------

    private fun checkMicrophonePermission() {
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

        beginRecognition()
    }

    private fun beginRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notifySpeechError(
                "O reconhecimento de voz não está disponível neste dispositivo."
            )
            return
        }

        try {
            recognizer?.destroy()

            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {
                        showStatus("Estou a ouvir...")
                    }

                    override fun onBeginningOfSpeech() {
                        showStatus("A reconhecer a tua voz...")
                    }

                    override fun onRmsChanged(
                        rmsdB: Float
                    ) {
                        // Pode ser usado para animar o indicador de voz.
                    }

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) {
                    }

                    override fun onEndOfSpeech() {
                        showStatus("A processar o áudio...")
                    }

                    override fun onError(error: Int) {
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
                                "Não consegui perceber. Tenta novamente."

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "O reconhecimento de voz está ocupado."

                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Não detetei fala."

                            else ->
                                "Ocorreu um erro no reconhecimento de voz."
                        }

                        notifySpeechError(message)
                    }

                    override fun onResults(
                        results: Bundle?
                    ) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val spokenText = matches
                            ?.firstOrNull()
                            .orEmpty()

                        if (spokenText.isNotBlank()) {
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "if (window.onNativeSpeechResult) " +
                                        "window.onNativeSpeechResult(" +
                                        "${JSONObject.quote(spokenText)});",
                                    null
                                )
                            }
                        } else {
                            notifySpeechError(
                                "Não consegui reconhecer a frase."
                            )
                        }
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
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )
            }

            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar reconhecimento.", e)
            notifySpeechError(
                "Não foi possível iniciar o reconhecimento de voz."
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
    // MODELO LOCAL LITERT-LM
    // ---------------------------------------------------------

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

                        if (!temporaryFile.renameTo(destination)) {
                            temporaryFile.copyTo(
                                destination,
                                overwrite = true
                            )
                            temporaryFile.delete()
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

        if (modelReady || modelLoading) {
            return
        }

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

    private fun initializeGemmaOnExecutor(
        file: File
    ) {
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

            showStatus("JARVIS pronto. Modelo local carregado.")
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
            showModelError("Escreve uma pergunta primeiro.")
            return
        }

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

    // ---------------------------------------------------------
    // RESPOSTAS PARA A INTERFACE HTML
    // ---------------------------------------------------------

    private fun showModelResponse(answer: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onModelResponse) " +
                    "window.onModelResponse(" +
                    "${JSONObject.quote(answer)});",
                null
            )
        }
    }

    private fun showModelError(message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onModelError) " +
                    "window.onModelError(" +
                    "${JSONObject.quote(message)});",
                null
            )
        }
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onNativeStatus) " +
                    "window.onNativeStatus(" +
                    "${JSONObject.quote(message)});",
                null
            )
        }
    }

    private fun notifySpeechError(message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if (window.onNativeSpeechError) " +
                    "window.onNativeSpeechError(" +
                    "${JSONObject.quote(message)});",
                null
            )
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

        // Coloca o encerramento na fila antes de desligá-la.
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

        super.onDestroy()
    }
}
