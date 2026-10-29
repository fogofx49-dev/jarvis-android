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
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
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
    private var recognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // Motor LiteRT-LM Local Oficial
    private var localEngine: Engine? = null
    private var localConversation: Conversation? = null
    private var isModelLoaded = false

    private var lastSpokenText = ""
    private var lastSpokenTime = 0L

    companion object {
        private const val TAG = "JARVIS_LITERT"
        private const val MIC_PERMISSION = 101
        private const val PICK_MODEL_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeTts()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    showStatus("J.A.R.V.I.S. online. Pronto para carregar a IA local.")
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(), "AndroidVoice")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun openModelFileSelector() {
        mainHandler.post {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                startActivityForResult(intent, PICK_MODEL_REQUEST)
                showStatus("Selecione o ficheiro do modelo (.litertlm / .bin)...")
                speak("A abrir explorador para selecionar o modelo local, chefe.")
            } catch (_: Exception) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_MODEL_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                backgroundExecutor.execute {
                    try {
                        val cachedFile = copyUriToCache(uri)
                        if (cachedFile != null) {
                            initLitertEngine(cachedFile.absolutePath)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao copiar ficheiro do modelo", e)
                        mainHandler.post { showStatus("Erro ao processar ficheiro de IA.") }
                    }
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx != -1) cursor.getString(idx) else "model.litertlm"
            } ?: "model.litertlm"

            val dest = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest
        } catch (_: Exception) { null }
    }

    private fun initLitertEngine(modelPath: String) {
        try {
            localConversation?.close()
            localEngine?.close()

            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU()
            )
            localEngine = Engine(config).apply {
                initialize()
            }
            localConversation = localEngine?.createConversation()
            isModelLoaded = true

            Log.i(TAG, "Motor LiteRT-LM inicializado com sucesso.")
            mainHandler.post {
                showStatus("IA local conectada com sucesso.")
                speak("Núcleo de inteligência local ativado e operacional, chefe.")
            }
        } catch (e: Exception) {
            isModelLoaded = false
            Log.e(TAG, "Falha ao iniciar motor LiteRT", e)
            mainHandler.post {
                showStatus("Erro ao iniciar motor de IA.")
                speak("Falha ao inicializar o modelo de inteligência local.")
            }
        }
    }

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale("pt", "BR"))
                tts.setSpeechRate(0.96f)
                tts.setPitch(0.92f)
            }
        }
    }

    private fun speak(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        mainHandler.post {
            try { recognizer?.cancel() } catch (_: Exception) {}
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_VOICE")
        }
    }

    private fun startListeningManual() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            return
        }
        mainHandler.post {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(p: Bundle?) { showStatus("A escutar...") }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(r: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() { showStatus("A processar...") }
                        override fun onError(e: Int) { showStatus("Escuta encerrada.") }
                        override fun onResults(results: Bundle?) {
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                            processCommand(text)
                        }
                        override fun onPartialResults(p: Bundle?) {}
                        override fun onEvent(e: Int, p: Bundle?) {}
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    })
                }
            } catch (_: Exception) {}
        }
    }

    private fun getBatteryLevel(): Int {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    private fun toggleFlashlight(enable: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Requer Android 6.0+."
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return "Indisponível."
        cm.setTorchMode(id, enable)
        return if (enable) "Lanterna ligada, chefe." else "Lanterna desligada."
    }

    private fun openApplication(appNameInput: String): String {
        val appName = appNameInput.lowercase(Locale.ROOT).trim()
        try {
            val intent = when {
                appName.contains("youtube") -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                appName.contains("google") -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                appName.contains("configurações") || appName.contains("settings") -> Intent(Settings.ACTION_SETTINGS)
                else -> packageManager.getLaunchIntentForPackage(appNameInput) ?: return "Não encontrei o aplicativo $appNameInput, chefe."
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return "A abrir $appName, chefe."
        } catch (_: Exception) {
            return "Não foi possível abrir o aplicativo."
        }
    }

    private fun processCommand(prompt: String) {
        val text = prompt.lowercase(Locale.ROOT).trim()

        // 1. Atalhos essenciais de hardware mantidos para controlo físico direto
        val hardwareAnswer = when {
            text.contains("bateria") -> "A bateria está a ${getBatteryLevel()} por cento, chefe."
            text.contains("lanterna") && (text.contains("liga") || text.contains("acende")) -> toggleFlashlight(true)
            text.contains("lanterna") && (text.contains("desliga") || text.contains("apaga")) -> toggleFlashlight(false)
            text.contains("horas") -> "Agora são ${SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())}."
            text.contains("youtube") -> openApplication("youtube")
            text.contains("google") -> openApplication("google")
            text.contains("configurações") -> openApplication("settings")
            else -> null
        }

        if (hardwareAnswer != null) {
            respond(hardwareAnswer)
            return
        }

        // 2. Consulta automática direcionada diretamente para o modelo LiteRT-LM carregado
        showStatus("A consultar IA local...")
        backgroundExecutor.execute {
            var aiResponse: String? = null

            if (isModelLoaded && localConversation != null) {
                try {
                    // Envia a pergunta diretamente para o Gemma / LiteRT-LM
                    aiResponse = localConversation!!.sendMessage(prompt)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro na inferência do modelo local", e)
                }
            }

            val finalOutput = if (aiResponse.isNullOrBlank()) {
                if (!isModelLoaded) {
                    "Nenhum modelo de IA carregado. Por favor, selecione o ficheiro do modelo primeiro."
                } else {
                    "Não consegui obter uma resposta do modelo local."
                }
            } else {
                aiResponse!!
            }

            mainHandler.post { respond(finalOutput) }
        }
    }

    private fun respond(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpokenTime) < 1500) return
        lastSpokenText = text
        lastSpokenTime = now

        mainHandler.post {
            webView.evaluateJavascript("if (typeof onCloudAIResponse === 'function') onCloudAIResponse(${JSONObject.quote(text)});", null)
            showStatus("Pronto.")
            speak(text)
        }
    }

    private fun showStatus(msg: String) {
        mainHandler.post {
            webView.evaluateJavascript("if (typeof onNativeStatus === 'function') onNativeStatus(${JSONObject.quote(msg)});", null)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface fun askModel(p: String) { processCommand(p) }
        @JavascriptInterface fun startListening() { startListeningManual() }
        @JavascriptInterface fun chooseModel() { openModelFileSelector() }
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { localConversation?.close() } catch (_: Exception) {}
        try { localEngine?.close() } catch (_: Exception) {}
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
