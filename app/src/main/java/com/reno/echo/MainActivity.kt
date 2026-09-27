package com.reno.echo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, String>? = null

    private val prefs by lazy { getSharedPreferences("echo", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.parseColor("#070B12"))
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#070B12"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val url = req.url.toString()
                    if (url.startsWith("file:")) return false
                    openView(url)
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        web.loadUrl("file:///android_asset/index.html")

        initTts()
    }

    // ── helpers ─────────────────────────────────────────────
    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun event(fn: String, obj: JSONObject) = js("window.$fn && window.$fn(${obj})")

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openView(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    // ── voice out (text to speech) ──────────────────────────
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val india = Locale("en", "IN")
                if (t.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) t.language = india
                else t.language = Locale.US
                t.setSpeechRate(prefs.getFloat("rate", 1.05f))
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        event("onSpeak", JSONObject().put("type", "start").put("id", utteranceId ?: ""))
                    }
                    override fun onDone(utteranceId: String?) {
                        event("onSpeak", JSONObject().put("type", "done").put("id", utteranceId ?: ""))
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        event("onSpeak", JSONObject().put("type", "done").put("id", utteranceId ?: ""))
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        event("onSpeak", JSONObject().put("type", "done").put("id", utteranceId ?: ""))
                    }
                })
                ttsReady = true
                pendingSpeak?.let { speakNow(it.first, it.second) }
                pendingSpeak = null
            }
        }
    }

    private fun speakNow(text: String, id: String) {
        val t = tts
        if (t == null || !ttsReady) {
            pendingSpeak = text to id
            return
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    // ── voice in (speech recognition) ───────────────────────
    private fun startListening(prompted: Boolean) {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            event("onSpeech", JSONObject().put("type", "error").put("code", "permission"))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            event("onSpeech", JSONObject().put("type", "error").put("code", "unavailable"))
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                event("onSpeech", JSONObject().put("type", "ready"))
            }
            override fun onBeginningOfSpeech() {
                event("onSpeech", JSONObject().put("type", "begin"))
            }
            override fun onRmsChanged(rmsdB: Float) {
                event("onSpeech", JSONObject().put("type", "rms").put("level", rmsdB.toDouble()))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                event("onSpeech", JSONObject().put("type", "end"))
            }
            override fun onError(error: Int) {
                val code = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "nomatch"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    SpeechRecognizer.ERROR_CLIENT -> "client"
                    else -> "other"
                }
                event("onSpeech", JSONObject().put("type", "error").put("code", code))
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: ""
                event("onSpeech", JSONObject().put("type", "final").put("text", text))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull() ?: ""
                if (text.isNotEmpty()) event("onSpeech", JSONObject().put("type", "partial").put("text", text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("lang", "en-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (prompted) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            event("onSpeech", JSONObject().put("type", "error").put("code", "client"))
        }
    }

    private fun stopListening() {
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            event("onMicPermission", JSONObject().put("granted", ok))
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        js("window.onAppPause && window.onAppPause()")
    }

    override fun onResume() {
        super.onResume()
        js("window.onAppResume && window.onAppResume()")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ── phone actions ───────────────────────────────────────
    private fun openInstalledApp(query: String): String {
        val q = query.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return ""
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }

        fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val nq = norm(q)
        val hit = apps.firstOrNull { norm(it.first) == nq }
            ?: apps.firstOrNull { norm(it.first).startsWith(nq) }
            ?: apps.firstOrNull { nq.length >= 3 && norm(it.first).contains(nq) }
            ?: apps.firstOrNull { nq.length >= 4 && nq.contains(norm(it.first)) && norm(it.first).length >= 3 }
            ?: return ""
        val launch = pm.getLaunchIntentForPackage(hit.second) ?: return ""
        return try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hit.first
        } catch (_: Exception) { "" }
    }

    private fun playOn(pkg: String, query: String): Boolean = try {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
        true
    } catch (_: Exception) { false }

    private fun torch(on: Boolean): Boolean = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else { cm.setTorchMode(id, on); true }
    } catch (_: Exception) { false }

    // ── the bridge the page talks to ────────────────────────
    inner class Bridge {
        @JavascriptInterface
        fun listen(prompted: Boolean) = runOnUiThread { startListening(prompted) }

        @JavascriptInterface
        fun stopListen() = runOnUiThread { stopListening() }

        @JavascriptInterface
        fun hasMicPermission(): Boolean = hasMic()

        @JavascriptInterface
        fun speak(text: String, id: String) = runOnUiThread { speakNow(text, id) }

        @JavascriptInterface
        fun stopSpeaking() = runOnUiThread { try { tts?.stop() } catch (_: Exception) {} }

        @JavascriptInterface
        fun setRate(rate: Float) {
            prefs.edit().putFloat("rate", rate).apply()
            tts?.setSpeechRate(rate)
        }

        @JavascriptInterface
        fun getPref(key: String): String = prefs.getString("p_$key", "") ?: ""

        @JavascriptInterface
        fun setPref(key: String, value: String) = prefs.edit().putString("p_$key", value).apply()

        @JavascriptInterface
        fun ask(system: String, history: String, search: Boolean, cb: String) {
            Thread {
                val out = JSONObject()
                try {
                    val key = prefs.getString("p_apikey", "") ?: ""
                    if (key.isBlank()) throw GeminiError(0, "NO_KEY")
                    val preferred = prefs.getString("p_model", "") ?: ""
                    val res = Gemini.ask(key, preferred, system, history, search)
                    if (res.model != preferred) prefs.edit().putString("p_model", res.model).apply()
                    out.put("text", res.text).put("model", res.model)
                } catch (e: GeminiError) {
                    out.put("error", e.message ?: "error").put("code", e.code)
                } catch (e: Exception) {
                    out.put("error", "NETWORK").put("code", -1)
                }
                js("window.onReply(${JSONObject.quote(cb)}, $out)")
            }.start()
        }

        @JavascriptInterface
        fun openApp(name: String): String = openInstalledApp(name)

        @JavascriptInterface
        fun openUrl(url: String): Boolean = openView(url)

        @JavascriptInterface
        fun play(query: String, where: String): Boolean {
            val pkg = if (where == "spotify") "com.spotify.music" else "com.google.android.youtube"
            if (playOn(pkg, query)) return true
            val q = Uri.encode(query)
            return if (where == "spotify") openView("https://open.spotify.com/search/$q")
            else openView("https://www.youtube.com/results?search_query=$q")
        }

        @JavascriptInterface
        fun flashlight(on: Boolean): Boolean = torch(on)

        @JavascriptInterface
        fun alarm(hour: Int, minute: Int, label: String): Boolean = try {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun timer(seconds: Int, label: String): Boolean = try {
            startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun dial(number: String): Boolean = try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun openSettings(which: String) {
            val action = when (which) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
                "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "voice" -> "com.android.settings.TTS_SETTINGS"
                "app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            runOnUiThread {
                try {
                    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (which == "app") i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (_: Exception) {
                    try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }
    }
}
