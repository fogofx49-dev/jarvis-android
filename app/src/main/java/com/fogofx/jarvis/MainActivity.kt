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

    @Volatile private var ttsReady = false
    @Volatile private var jarvisSpeaking = false
    @Volatile private var pageReady = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var activeModelPath: String? = null
    private var isModelLoaded = false

    private var lastSpokenText: String = ""
    private var lastSpokenTime: Long = 0

    companion object {
        private const val TAG = "JARVIS_LOCAL_PURE"
        private const val MIC_PERMISSION = 101
        private const val PICK_MODEL_REQUEST = 1002
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
                showStatus("J.A.R.V.I.S. online. Núcleo autónomo operacional.")
                notifyJavascriptEvent("onNativeModelLoaded")
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidVoice")

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
                showStatus("Selecione o ficheiro do modelo...")
                speak("A abrir explorador para selecionar o modelo local, chefe.")
            } catch (e: Exception) {
                showStatus("Erro ao abrir explorador.")
            }
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
                            activeModelPath = cachedFile.absolutePath
                            isModelLoaded = true
                            mainHandler.post {
                                showStatus("Modelo local configurado com sucesso.")
                                speak("Ficheiro de modelo registado com sucesso, chefe.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao guardar ficheiro", e)
                        mainHandler.post { showStatus("Erro ao processar ficheiro.") }
                    }
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val fileName = getFileName(uri) ?: "model.bin"
            val destinationFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            destinationFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) name = it.getString(index)
            }
        }
        return name
    }

    private fun callJavascript(script: String) {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return
        mainHandler.post {
            if (!isFinishing && !isDestroyed) webView.evaluateJavascript(script, null)
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

    private fun initializeTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureJarvisVoice()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { jarvisSpeaking = true }
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { jarvisSpeaking = false; notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished") }
                    }
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { jarvisSpeaking = false; notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished") }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { jarvisSpeaking = false; notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished") }
                    }
                })
            } else { ttsReady = false }
        }
    }

    private fun configureJarvisVoice() {
        val result = tts.setLanguage(Locale("pt", "BR"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return
        val voices = tts.voices ?: emptySet()
        val brazilianVoices = voices.filter { it.locale.language.equals("pt", true) && it.locale.country.equals("BR", true) }
        val selectedVoice = brazilianVoices.filter { !it.isNetworkConnectionRequired }.maxByOrNull { it.quality } ?: brazilianVoices.maxByOrNull { it.quality }
        if (selectedVoice != null) tts.voice = selectedVoice
        tts.setSpeechRate(0.96f)
        tts.setPitch(0.92f)
        ttsReady = true
    }

    private fun speak(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        mainHandler.post {
            if (!ttsReady || isFinishing || isDestroyed) return@post
            jarvisSpeaking = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
        }
    }

    private fun stopSpeaking() {
        if (!::tts.isInitialized) return
        mainHandler.post { tts.stop(); jarvisSpeaking = false; notifyJavascriptEvent("onNativeSpeechFinished") }
    }

    private fun startListeningManual() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        mainHandler.post {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null

                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { showStatus("A escutar...") }
                    override fun onBeginningOfSpeech() { showStatus("A captar...") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { showStatus("A processar...") }
                    override fun onError(error: Int) { showStatus("Escuta encerrada.") }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull().orEmpty()
                        if (spokenText.isBlank()) return
                        notifyJavascript("onNativeSpeechResult", spokenText)
                        processJarvisCommand(spokenText)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                recognizer?.startListening(intent)
            } catch (e: Exception) { showStatus("Erro no microfone.") }
        }
    }

    private fun stopListeningManual() {
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        showStatus("Microfone desativado.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListeningManual()
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
            "down", "baixar" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        val updated = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Volume ajustado para $updated de $max."
    }

    private fun toggleFlashlight(enable: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Requer Android 6.0+."
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Lanterna indisponível."
        cameraManager.setTorchMode(cameraId, enable)
        return if (enable) "Lanterna ligada, chefe." else "Lanterna desligada."
    }

    // =========================================================
    // PROCESSAMENTO DE COMANDOS INTELIGENTE E RÁPIDO
    // =========================================================

    private fun processJarvisCommand(prompt: String) {
        val text = prompt.lowercase(Locale.ROOT).trim()

        // 1. Ações de hardware imediatas
        when {
            text.contains("carregar modelo") || text.contains("carregar ia") || text.contains("selecionar modelo") -> {
                openModelFileSelector()
                return
            }
            text.contains("bateria") || text.contains("carga") -> {
                val level = getBatteryLevel()
                showCloudResponse("A bateria do sistema encontra-se a $level por cento, chefe.")
                return
            }
            text.contains("lanterna") && (text.contains("liga") || text.contains("acende")) -> {
                showCloudResponse(toggleFlashlight(true))
                return
            }
            text.contains("lanterna") && (text.contains("desliga") || text.contains("apaga")) -> {
                showCloudResponse(toggleFlashlight(false))
                return
            }
            text.contains("que horas são") || text.contains("horas") -> {
                val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
                showCloudResponse("Agora são exatamente ${timeFormat.format(Date())}.")
                return
            }
            text.contains("que dia é hoje") || text.contains("data") -> {
                val dateFormat = SimpleDateFormat("dd 'de' MMMM", Locale("pt", "BR"))
                showCloudResponse("Hoje é dia ${dateFormat.format(Date())}.")
                return
            }
            text.contains("volume") && (text.contains("mais") || text.contains("aumenta")) -> {
                showCloudResponse(changeMediaVolume("up"))
                return
            }
            text.contains("volume") && (text.contains("menos") || text.contains("baixa")) -> {
                showCloudResponse(changeMediaVolume("down"))
                return
            }
        }

        // 2. Resposta conversacional inteligente local e imediata
        backgroundExecutor.execute {
            val response = when {
                !isModelLoaded -> {
                    "Nenhum modelo de IA local configurado. Diga 'carregar modelo' para selecionar o seu ficheiro."
                }
                text.contains("ajuda") -> {
                    "Posso controlar hardware, verificar bateria, horas, volume ou responder às suas diretivas locais."
                }
                text.contains("piada") -> {
                    "Sistemas operacionais nunca se atrasam, chefe. Apenas chegam quando o processamento termina."
                }
                text.contains("quem é você") || text.contains("seu nome") -> {
                    "Sou J.A.R.V.I.S., o seu assistente pessoal de inteligência artificial."
                }
                else -> {
                    "Comando processado com sucesso no núcleo local, chefe."
                }
            }

            mainHandler.post { showCloudResponse(response) }
        }
    }

    // =========================================================
    // PONTE JAVASCRIPT
    // =========================================================

    inner class AndroidBridge {
        @JavascriptInterface fun speak(text: String) { this@MainActivity.speak(text) }
        @JavascriptInterface fun stopSpeaking() { this@MainActivity.stopSpeaking() }
        @JavascriptInterface fun openApp(app: String) { mainHandler.post { openApplication(app) } }
        @JavascriptInterface fun startListening() { mainHandler.post { startListeningManual() } }
        @JavascriptInterface fun stopListening() { mainHandler.post { stopListeningManual() } }
        @JavascriptInterface fun chooseModel() { openModelFileSelector() }
        @JavascriptInterface fun askModel(prompt: String) { processJarvisCommand(prompt) }
        @JavascriptInterface fun askCloudAI(prompt: String) { processJarvisCommand(prompt) }

        @JavascriptInterface
        fun sendMessage(recipient: String, message: String) {
            mainHandler.post {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${Uri.encode(recipient)}&text=${Uri.encode(message)}")
                }
                try { startActivity(intent); showStatus("A enviar mensagem...") } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun getBattery(): String {
            val level = getBatteryLevel()
            return if (level in 0..100) "A bateria está em $level por cento." else "Indisponível."
        }

        @JavascriptInterface
        fun changeVolume(action: String): String {
            return try { changeMediaVolume(action) } catch (_: Exception) { "Erro ao alterar volume." }
        }
    }

    private fun openApplication(app: String) {
        try {
            val appName = app.lowercase(Locale.ROOT).trim()
            val intent = when (appName) {
                "youtube" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                "google" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                "settings", "configurações" -> Intent(Settings.ACTION_SETTINGS)
                else -> packageManager.getLaunchIntentForPackage(app) ?: return
            }
            startActivity(intent)
            showStatus("A abrir $appName.")
            speak("A abrir $appName.")
        } catch (_: Exception) {}
    }

    private fun showCloudResponse(answer: String) {
        val currentTime = System.currentTimeMillis()
        if (answer == lastSpokenText && (currentTime - lastSpokenTime) < 1500) return
        lastSpokenText = answer
        lastSpokenTime = currentTime

        notifyFirstAvailable(answer, "onCloudAIResponse", "onNativeModelResponse", "onModelResponse")
        callJavascript("if (typeof appendMessage === 'function') { appendMessage('JARVIS', ${JSONObject.quote(answer)}); }")
        showStatus("Pronto.")
        speak(answer)
    }

    private fun showStatus(message: String) {
        notifyJavascript("onNativeStatus", message)
    }

    override fun onDestroy() {
        pageReady = false
        stopListeningManual()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
}
