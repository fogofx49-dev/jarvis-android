
package com.fogofx.jarvis

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.speech.tts.TextToSpeech
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    @Volatile private var ttsReady = false

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

        webView.addJavascriptInterface(
            AndroidVoice(),
            "AndroidVoice"
        )

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class AndroidVoice {

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
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}

