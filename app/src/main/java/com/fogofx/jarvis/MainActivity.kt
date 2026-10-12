
package com.fogofx.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.Locale
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var ttsReady = false

    companion object {
        private const val MIC_PERMISSION = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("pt", "BR"))
                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(AndroidBridge(), "AndroidVoice")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
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
                            android.net.Uri.parse("https://m.youtube.com/")
                        )

                        "google" -> Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.google.com/")
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
                if (!SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    showStatus("Não há serviço de reconhecimento de voz disponível.")
                    return@runOnUiThread
                }

                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
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
    }
    
    
