
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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            configureJarvisVoice(status)
        }

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

    // Configura a voz do JARVIS em português brasileiro.
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

        // Lista as vozes brasileiras disponíveis no motor TTS.
        val availableVoices = tts.voices
            ?.filter {
                it.locale.language == "pt" &&
                it.locale.country == "BR"
            }
            .orEmpty()

        // Mostra os identificadores das vozes no registo do Android.
        availableVoices.forEach { voice ->
            android.util.Log.d(
                "JARVIS_VOICES",
                "Nome=${voice.name}, " +
                "Idioma=${voice.locale}, " +
                "Rede=${voice.isNetworkConnectionRequired}, " +
                "Qualidade=${voice.quality}"
            )
        }

        // Tenta selecionar uma voz identificada como masculina.
        val selectedVoice = availableVoices
            .filter { !it.isNetworkConnectionRequired }
            .maxByOrNull { voice ->
                val name = voice.name.lowercase(Locale.ROOT)

                when {
                    "male" in name ||
                    "mascul" in name ||
                    "man" in name -> 3

                    "female" in name ||
                    "fem" in name ||
                    "woman" in name -> 0

                    else -> 1
                }
            }

        if (selectedVoice != null) {
            tts.voice = selectedVoice
        }

        tts.setSpeechRate(0.94f)
        tts.setPitch(0.92f)
        ttsReady = true

        val voiceNames = availableVoices
            .joinToString(", ") { it.name }

        showStatus(
            if (selectedVoice != null) {
                "Voz: ${selectedVoice.name}"
            } else {
                "Voz padrão pronta • Português BR"
            }
        )

        android.util.Log.d(
            "JARVIS_VOICES",
            "Vozes pt-BR disponíveis: $voiceNames"
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


        } catch (e: Exception) {
            ttsReady = false
            showStatus("Erro ao configurar a voz do JARVIS.")
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                if (ttsReady && text.isNotBlank()) {
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
                    val intent = when (app.lowercase()) {
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
                    showStatus("Não consegui abrir essa aplicação.")
                }
            }
        }

        @JavascriptInterface
        fun startListening() {
            runOnUiThread {
                if (!SpeechRecognizer.isRecognitionAvailable(
                        this@MainActivity
                    )
                ) {
                    showStatus(
                        "Não há serviço de reconhecimento de voz disponível."
                    )
                    return@runOnUiThread
                }

                if (
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
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

        @JavascriptInterface
        fun askModel(prompt: String) {
            val question = prompt.trim()

            if (question.isBlank()) return

            if (!modelReady) {
                showModelResponse(
                    "O Gemma ainda não está pronto. " +
                    "Seleciona o modelo e aguarda a inicialização."
                )
                return
            }

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
                    showModelResponse(
                        "Ocorreu um erro ao gerar a resposta do Gemma."
                    )
                }
            }
        }
    }

    private fun showModelResponse(answer: String) {
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "onNativeModelResponse(" +
                    "${JSONObject.quote(answer)})",
                    null
                )
            }
        }
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            if (!isFinishing && ::webView.isInitialized) {
                webView.evaluateJavascript(
                    "onNativeSpeechError(${JSONObject.quote(message)})",
                    null
                )
            }
        }
    }

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
                                webView.evaluateJavascript(
                                    "onNativeSpeechResult(" +
                                    "${JSONObject.quote(text)})",
                                    null
                                )
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
            showStatus("Não foi possível iniciar o microfone.")
        }
    }

    private fun openModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        startActivityForResult(intent, MODEL_PICKER)
    }

    private fun initializeGemma() {
        val file = File(filesDir, "model.litertlm")

        if (!file.exists()) {
            showStatus(
                "Seleciona o ficheiro do modelo .litertlm."
            )
            return
        }

        if (modelReady || modelLoading) return

        modelLoading = true
        showStatus("A iniciar o Gemma...")

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

                showStatus(
                    "Erro ao iniciar o Gemma: " +
                    (e.message ?: "erro desconhecido")
                )
            } finally {
                modelLoading = false
            }
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
            requestCode == MODEL_PICKER &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return
            modelUri = uri

            showStatus("A copiar o modelo para o JARVIS…")

            Thread {
                try {
                    val destination = File(
                        filesDir,
                        "model.litertlm"
                    )

                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) {
                            "Não foi possível abrir o ficheiro."
                        }

                        FileOutputStream(destination).use { output ->
                            input.copyTo(output)
                        }
                    }

                    modelFile = destination

                    showStatus(
                        "Modelo copiado! A iniciar o Gemma..."
                    )

                    initializeGemma()

                } catch (e: Exception) {
                    showStatus(
                        "Erro ao copiar o modelo. " +
                        "Verifica o ficheiro e o espaço disponível."
                    )
                }
            }.start()
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

        try {
            modelConversation?.close()
        } catch (_: Exception) {
        }

        try {
            modelEngine?.close()
        } catch (_: Exception) {
        }

        aiExecutor.shutdownNow()

        super.onDestroy()
    }
}


