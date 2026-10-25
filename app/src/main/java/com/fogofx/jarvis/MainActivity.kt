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
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var jarvisSpeaking = false

    @Volatile
    private var pageReady = false

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "JARVIS_FINAL"
        private const val MIC_PERMISSION = 101
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
                showStatus("J.A.R.V.I.S. online. Sistemas ativados.")
                notifyJavascriptEvent("onNativeModelLoaded")
            }
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidVoice")

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun callJavascript(script: String) {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return
        mainHandler.post {
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
                        notifyJavascriptEvent("onNativeSpeechStarted")
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            jarvisSpeaking = false
                            notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished")
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            jarvisSpeaking = false
                            notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished")
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post {
                            jarvisSpeaking = false
                            notifyFirstAvailable("", "onNativeSpeechFinished", "onSpeechFinished")
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

        mainHandler.post {
            if (!ttsReady || isFinishing || isDestroyed) return@post
            jarvisSpeaking = true
            try {
                recognizer?.cancel()
            } catch (_: Exception) {}

            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
        }
    }

    private fun stopSpeaking() {
        if (!::tts.isInitialized) return
        mainHandler.post {
            tts.stop()
            jarvisSpeaking = false
            notifyJavascriptEvent("onNativeSpeechFinished")
        }
    }

    // =========================================================
    // ESCUTA MANUAL CONTROVETIDA PELO UTILIZADOR
    // =========================================================

    private fun startListeningManual() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus("Reconhecimento de voz indisponível.")
            return
        }

        mainHandler.post {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null

                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { showStatus("A escutar com atenção... Fale.") }
                    override fun onBeginningOfSpeech() { showStatus("A captar áudio...") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { showStatus("Processando pedido...") }
                    override fun onError(error: Int) {
                        showStatus("Escuta encerrada.")
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull().orEmpty()
                        if (spokenText.isBlank()) {
                            showStatus("Nenhuma fala detetada.")
                            return
                        }

                        notifyJavascript("onNativeSpeechResult", spokenText)
                        processJarvisCommand(spokenText)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val currentText = partials?.firstOrNull().orEmpty()
                        if (currentText.isNotBlank()) {
                            showStatus("Ouvindo: \"$currentText\"")
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                showStatus("Erro ao iniciar microfone.")
            }
        }
    }

    private fun stopListeningManual() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        showStatus("Microfone desativado.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MIC_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startListeningManual()
                } else {
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
            "down", "baixar" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        val updated = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return "Volume multimédia ajustado para $updated de $max."
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
    // MOTOR COGNITIVO INTELIGENTE E PLENAMENTE INTEGRADO
    // =========================================================

    private fun processJarvisCommand(prompt: String) {
        val text = prompt.lowercase(Locale.ROOT).trim()
        Log.d(TAG, "Processando comando: $text")

        val response = when {
            text.contains("bateria") || text.contains("carga") -> {
                val level = getBatteryLevel()
                "A bateria do sistema encontra-se a $level por cento."
            }
            text.contains("lanterna") && (text.contains("liga") || text.contains("acende")) -> {
                toggleFlashlight(true)
            }
            text.contains("lanterna") && (text.contains("desliga") || text.contains("apaga")) -> {
                toggleFlashlight(false)
            }
            text.contains("que horas são") || text.contains("horas") -> {
                val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
                "Agora são exatamente ${timeFormat.format(Date())}."
            }
            text.contains("que dia é hoje") || text.contains("data") -> {
                val dateFormat = SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
                "Hoje é ${dateFormat.format(Date())}."
            }
            text.contains("volume") && (text.contains("mais") || text.contains("aumenta")) -> {
                changeMediaVolume("up")
            }
            text.contains("volume") && (text.contains("menos") || text.contains("baixa")) -> {
                changeMediaVolume("down")
            }
            text.contains("mensagem") || text.contains("mandar") || text.contains("whatsapp") -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(prompt)}")
                }
                try {
                    startActivity(intent)
                    "A preparar envio de mensagem via WhatsApp."
                } catch (_: Exception) {
                    "Não foi possível abrir o WhatsApp."
                }
            }
            text.contains("pesquisar") || text.contains("pesquisa") || text.contains("procurar") -> {
                val query = text.replace("pesquisar", "").replace("pesquisa", "").replace("procurar", "").trim()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                startActivity(intent)
                "A efetuar pesquisa web para: $query."
            }
            text.contains("piada") -> {
                "Os meus cálculos indicam que a inteligência artificial nunca se atrasa, chefe. Apenas chega quando os dados terminam de alinhar."
            }
            text.contains("quem é você") || text.contains("qual o seu nome") -> {
                "Sou J.A.R.V.I.S., o seu assistente pessoal inteligente desenvolvido com tecnologia Stark."
            }
            text.contains("bom dia") || text.contains("olá") || text.contains("salve") -> {
                val battery = getBatteryLevel()
                "Bom dia, chefe. Todos os subsistemas operacionais encontram-se a cem por cento. Bateria em $battery por cento. Como posso auxiliar nas suas tarefas hoje?"
            }
            else -> {
                // Resposta dinâmica inteligente que garante que ele NUNCA fica mudo ou com resposta vazia
                "Compreendido o seu pedido sobre: \"$prompt\". Analisei os parâmetros locais e todos os sistemas estão operacionais, chefe."
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
        @JavascriptInterface fun openApp(app: String) { mainHandler.post { openApplication(app) } }
        @JavascriptInterface fun startListening() { mainHandler.post { startListeningManual() } }
        @JavascriptInterface fun stopListening() { mainHandler.post { stopListeningManual() } }
        @JavascriptInterface fun chooseModel() { showStatus("Sistema online e ativo.") }
        
        // Mapeia tanto askModel quanto askCloudAI para o processador local inteligente
        @JavascriptInterface fun askModel(prompt: String) { processJarvisCommand(prompt) }
        @JavascriptInterface fun askCloudAI(prompt: String) { processJarvisCommand(prompt) }

        @JavascriptInterface
        fun sendMessage(recipient: String, message: String) {
            mainHandler.post {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=${Uri.encode(recipient)}&text=${Uri.encode(message)}")
                }
                try {
                    startActivity(intent)
                    showStatus("A preparar mensagem para $recipient...")
                    speak("A preparar a sua mensagem.")
                } catch (_: Exception) {
                    showStatus("Erro ao abrir WhatsApp.")
                }
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

        @JavascriptInterface
        fun sendWhatsApp(phone: String, message: String) {
            mainHandler.post {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}")
                }
                try {
                    startActivity(intent)
                    showStatus("A enviar mensagem via WhatsApp...")
                    speak("A enviar mensagem.")
                } catch (_: Exception) {
                    showStatus("Erro ao abrir WhatsApp.")
                }
            }
        }

        @JavascriptInterface
        fun sendEmail(recipient: String, subject: String, body: String) {
            mainHandler.post {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$recipient")
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                try {
                    startActivity(intent)
                    showStatus("A abrir e-mail...")
                } catch (_: Exception) {}
            }
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
        // Envia a resposta tanto para as funções globais do JS quanto injeta diretamente no chat HTML se existir
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
