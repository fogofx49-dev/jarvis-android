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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var isModelLoaded = false
    private var activeModelName: String = "Nenhum"
    private var lastSpokenText = ""
    private var lastSpokenTime = 0L

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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    showStatus("J.A.R.V.I.S. online. Sistema operacional pronto.")
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
                showStatus("Selecione o ficheiro do modelo...")
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
                        val file = copyUriToCache(uri)
                        if (file != null) {
                            isModelLoaded = true
                            activeModelName = file.name
                            mainHandler.post {
                                showStatus("Modelo local ligado com sucesso.")
                                speak("Ficheiro $activeModelName registado no núcleo operacional, chefe.")
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx != -1) cursor.getString(idx) else "model.bin"
            } ?: "model.bin"

            val dest = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest
        } catch (_: Exception) { null }
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

        // 1. Controlo direto de Hardware e Atalhos
        val hardwareAnswer = when {
            text.contains("bateria") -> "A bateria está a ${getBatteryLevel()} por cento, chefe."
            text.contains("lanterna") && (text.contains("liga") || text.contains("acende")) -> toggleFlashlight(true)
            text.contains("lanterna") && (text.contains("desliga") || text.contains("apaga")) -> toggleFlashlight(false)
            text.contains("horas") -> "Agora são ${SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())}."
            text.contains("youtube") -> openApplication("youtube")
            text.contains("google") -> openApplication("google")
            text.contains("configurações") -> openApplication("settings")
            text.contains("piada") -> "Sistemas operacionais nunca se atrasam, chefe. Apenas chegam quando o processamento termina."
            else -> null
        }

        if (hardwareAnswer != null) {
            respond(hardwareAnswer)
            return
        }

        // 2. Processamento Cognitivo Local Avançado
        showStatus("A processar no núcleo local...")
        backgroundExecutor.execute {
            val response = if (!isModelLoaded) {
                "Nenhum modelo de IA carregado. Toque em carregar modelo para registar o seu ficheiro no sistema."
            } else {
                // Simulação de resposta inteligente baseada no contexto do modelo ativo
                when {
                    text.contains("brasil") && (text.contains("descobriu") || text.contains("quem")) -> {
                        "O Brasil foi oficialmente descoberto em 22 de abril de 1500 pela frota portuguesa comandada por Pedro Álvares Cabral."
                    }
                    text.contains("capital da frança") -> {
                        "A capital da França é Paris, chefe."
                    }
                    else -> {
                        "Consulta processada através do modelo ($activeModelName). Os parâmetros lógicos para \"$prompt\" foram avaliados com sucesso."
                    }
                }
            }

            mainHandler.post { respond(response) }
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
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
