package com.reno.echo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val ACTION_LISTEN = "com.reno.echo.LISTEN"
        const val ACTION_PHOTO = "com.reno.echo.PHOTO"
        @Volatile var visible = false

        fun inCall(c: Context): Boolean = try {
            val m = (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
            m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
        } catch (_: Exception) { false }

        private const val RC_MIC = 7
        private const val RC_CONTACTS = 8
        private const val RC_CALL = 9
        private const val RC_GENERIC = 10
        private const val RC_CAMERA = 21
        private const val RC_GALLERY = 22
    }

    private lateinit var web: WebView
    private var pageReady = false
    private var pendingAction: Pair<String, String>? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, String>? = null

    private var pendingContact: Triple<String, String, Boolean>? = null   // (text, js callback, split?)
    private var pendingCall: String? = null
    private var photoUri: Uri? = null
    private var photoCb: String = ""
    @Volatile private var lastImage: String? = null

    private val prefs by lazy { getSharedPreferences("echo", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.parseColor("#05070D"))
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#05070D"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val url = req.url.toString()
                    if (url.startsWith("file:")) return false
                    openView(url)
                    return true
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    deliverAction()
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        web.loadUrl("file:///android_asset/index.html")

        initTts()
        Reminders.channels(this)
        WakeService.channels(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val a = i.action ?: return
        when (a) {
            ACTION_LISTEN, Intent.ACTION_ASSIST, "android.intent.action.VOICE_COMMAND" -> {
                if (i.getBooleanExtra("fromWake", false) && Build.VERSION.SDK_INT >= 27) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                }
                val cmd = i.getStringExtra("cmd") ?: ""
                pendingAction = (if (cmd.isNotEmpty()) "command" else "listen") to cmd
            }
            ACTION_PHOTO -> pendingAction = "photo" to ""
            else -> return
        }
        i.action = null
        deliverAction()
    }

    private fun deliverAction() {
        val p = pendingAction ?: return
        if (!pageReady) return
        pendingAction = null
        web.postDelayed({
            js("window.externalAction && window.externalAction(${JSONObject.quote(p.first)}, ${JSONObject.quote(p.second)})")
        }, 350)
    }

    // ── helpers ─────────────────────────────────────────────
    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    private fun event(fn: String, obj: Any) = js("window.$fn && window.$fn($obj)")

    private fun cb(fn: String, cb: String, obj: Any) = js("window.$fn && window.$fn(${JSONObject.quote(cb)}, $obj)")

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun hasMic() = has(Manifest.permission.RECORD_AUDIO)

    private fun openView(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    private fun launch(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    // ── voice out (text to speech) ──────────────────────────
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                applyTtsLang(prefs.getString("lang", "en-IN") ?: "en-IN")
                t.setSpeechRate(prefs.getFloat("rate", 1.05f))
                t.setPitch(prefs.getFloat("pitch", 1.0f))
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

    private fun applyTtsLang(code: String): Boolean {
        val t = tts ?: return false
        val loc = if (code == "ta-IN") Locale("ta", "IN") else Locale("en", "IN")
        return if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
            t.language = loc; true
        } else {
            t.language = if (code == "ta-IN") Locale("en", "IN").let {
                if (t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE) it else Locale.US
            } else Locale.US
            code != "ta-IN"
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
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
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
                event("onSpeech", JSONObject().put("type", "final").put("text", list?.firstOrNull() ?: ""))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isNotEmpty()) event("onSpeech", JSONObject().put("type", "partial").put("text", text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getString("lang", "en-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (prompted) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
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

    // ── lifecycle ───────────────────────────────────────────
    override fun onPause() {
        super.onPause()
        visible = false
        stopListening()
        js("window.onAppPause && window.onAppPause()")
    }

    override fun onResume() {
        super.onResume()
        visible = true
        js("window.onAppResume && window.onAppResume()")
    }

    override fun onDestroy() {
        visible = false
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            RC_MIC -> {
                val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                event("onMicPermission", JSONObject().put("granted", ok))
            }
            RC_CONTACTS -> {
                val p = pendingContact ?: return
                pendingContact = null
                Thread { resolveContact(p.first, p.second, p.third) }.start()
            }
            RC_CALL -> {
                val n = pendingCall ?: return
                pendingCall = null
                placeCall(n)
            }
        }
        js("window.onPerms && window.onPerms()")
    }

    // ── contacts & calls ────────────────────────────────────
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    private fun allContacts(): List<Pair<String, String>> {
        val people = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    people.add(name to num)
                }
            }
        } catch (_: Exception) {}
        return people
    }

    private fun match(people: List<Pair<String, String>>, spoken: String, loose: Boolean): Pair<String, String>? {
        val q = norm(spoken)
        if (q.isEmpty()) return null
        val nq = q.replace(" ", "")
        return people.firstOrNull { norm(it.first) == q }
            ?: people.firstOrNull { norm(it.first).replace(" ", "") == nq }
            ?: people.firstOrNull { norm(it.first).startsWith("$q ") }
            ?: people.firstOrNull { norm(it.first).split(" ").any { w -> w == q } }
            ?: if (loose) people.firstOrNull { q.length >= 3 && norm(it.first).replace(" ", "").contains(nq) } else null
    }

    /**
     * Finds who Paul means. split=false: the whole text is the name.
     * split=true: "tharun i'm on the way" -> name = longest leading words that match a contact, rest = message.
     */
    private fun resolveContact(text: String, cbId: String, split: Boolean) {
        val out = JSONObject()
        if (!has(Manifest.permission.READ_CONTACTS)) {
            cb("onContact", cbId, out.put("status", "denied"))
            return
        }
        val people = allContacts()
        if (!split) {
            val hit = match(people, text, true)
            if (hit == null) out.put("status", "notfound")
            else out.put("status", "found").put("name", hit.first).put("number", hit.second)
        } else {
            val words = text.trim().split(Regex("\\s+"))
            var done = false
            for (k in minOf(3, words.size) downTo 1) {
                val name = words.take(k).joinToString(" ")
                val hit = match(people, name, false) ?: continue
                val rest = words.drop(k).joinToString(" ")
                    .replace(Regex("^(saying|that|say|to say|and say|message)\\s+", RegexOption.IGNORE_CASE), "")
                out.put("status", "found").put("name", hit.first).put("number", hit.second).put("rest", rest)
                done = true
                break
            }
            if (!done) out.put("status", "notfound")
        }
        cb("onContact", cbId, out)
    }

    private fun placeCall(number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (has(Manifest.permission.CALL_PHONE)) {
            if (launch(Intent(Intent.ACTION_CALL, uri))) return
        } else if (!prefs.getBoolean("askedCall", false)) {
            prefs.edit().putBoolean("askedCall", true).apply()
            pendingCall = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), RC_CALL)
            return
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
    }

    /** Indian numbers without a country code get +91, as WhatsApp needs it. */
    private fun waNumber(raw: String): String {
        var d = raw.replace(Regex("[^0-9+]"), "")
        if (d.startsWith("+")) return d.drop(1)
        d = d.trimStart('0')
        return if (d.length == 10) "91$d" else d
    }

    // ── apps ────────────────────────────────────────────────
    private fun openInstalledApp(query: String): String {
        val q = query.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return ""
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }

        fun n(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val nq = n(q)
        val hit = apps.firstOrNull { n(it.first) == nq }
            ?: apps.firstOrNull { n(it.first).startsWith(nq) }
            ?: apps.firstOrNull { nq.length >= 3 && n(it.first).contains(nq) }
            ?: apps.firstOrNull { nq.length >= 4 && nq.contains(n(it.first)) && n(it.first).length >= 3 }
            ?: return ""
        val launchI = pm.getLaunchIntentForPackage(hit.second) ?: return ""
        return if (launch(launchI)) hit.first else ""
    }

    private fun installed(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun playOn(pkg: String, query: String): Boolean = launch(
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
    )

    private fun torch(on: Boolean): Boolean = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else { cm.setTorchMode(id, on); true }
    } catch (_: Exception) { false }

    // ── photos ──────────────────────────────────────────────
    private fun takePhoto(source: String, cbId: String) {
        photoCb = cbId
        if (source == "gallery") {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            try { startActivityForResult(Intent.createChooser(i, "Pick a photo for " + getString(R.string.app_name)), RC_GALLERY) }
            catch (_: Exception) { cb("onPhoto", cbId, JSONObject().put("ok", false).put("error", "nogallery")) }
            return
        }
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoUri = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "echo_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + getString(R.string.app_name))
                }
                photoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                photoUri?.let { i.putExtra(MediaStore.EXTRA_OUTPUT, it) }
            } catch (_: Exception) { photoUri = null }
        }
        try { startActivityForResult(i, RC_CAMERA) }
        catch (_: Exception) { cb("onPhoto", cbId, JSONObject().put("ok", false).put("error", "nocamera")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_CAMERA && requestCode != RC_GALLERY) return
        val cbId = photoCb
        val camUri = photoUri
        photoUri = null
        if (resultCode != RESULT_OK) {
            camUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            cb("onPhoto", cbId, JSONObject().put("ok", false).put("error", "cancelled"))
            return
        }
        Thread {
            val out = JSONObject()
            try {
                val bmp: Bitmap? = when {
                    requestCode == RC_GALLERY && data?.data != null -> decode(data.data!!)
                    camUri != null -> decode(camUri)
                    else -> data?.extras?.get("data") as? Bitmap
                }
                camUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                if (bmp == null) throw Exception("no image")
                val big = scale(bmp, 1280)
                lastImage = Base64.encodeToString(jpeg(big, 85), Base64.NO_WRAP)
                val thumb = scale(bmp, 360)
                out.put("ok", true).put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(jpeg(thumb, 72), Base64.NO_WRAP))
            } catch (e: Exception) {
                out.put("ok", false).put("error", "read")
            }
            cb("onPhoto", cbId, out)
        }.start()
    }

    private fun decode(uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        if (rot == 0f) return bmp
        val m = Matrix().apply { postRotate(rot) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scale(b: Bitmap, max: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= max) return b
        val f = max.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray {
        val s = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, s)
        return s.toByteArray()
    }

    // ── permissions status for the settings screen ──────────
    private fun permsJson(): JSONObject {
        val am = getSystemService(AlarmManager::class.java)
        return JSONObject()
            .put("mic", hasMic())
            .put("contacts", has(Manifest.permission.READ_CONTACTS))
            .put("phone", has(Manifest.permission.CALL_PHONE))
            .put("notify", Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))
            .put("overlay", Settings.canDrawOverlays(this))
            .put("writeSettings", Settings.System.canWrite(this))
            .put("exactAlarm", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            .put("bg", WakeService.running)
            .put("tamilVoice", tts?.let { it.isLanguageAvailable(Locale("ta", "IN")) >= TextToSpeech.LANG_AVAILABLE } ?: false)
    }

    private fun openSpecial(which: String) {
        val pkg = Uri.parse("package:$packageName")
        val i = when (which) {
            "wifi" -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            "internet", "data" -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display", "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound", "volume" -> Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "hotspot" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "voice" -> Intent("com.android.settings.TTS_SETTINGS")
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "writeSettings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            "exactAlarm" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else Intent(Settings.ACTION_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "defaultApps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            "battery_opt" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        runOnUiThread {
            if (!launch(i)) {
                if (which == "assistant") { if (launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) return@runOnUiThread }
                if (!launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))) launch(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    // ── the bridge the page talks to ────────────────────────
    inner class Bridge {
        // voice
        @JavascriptInterface fun listen(prompted: Boolean) = runOnUiThread { startListening(prompted) }
        @JavascriptInterface fun stopListen() = runOnUiThread { stopListening() }
        @JavascriptInterface fun hasMicPermission(): Boolean = hasMic()
        @JavascriptInterface fun speak(text: String, id: String) = runOnUiThread { speakNow(text, id) }
        @JavascriptInterface fun stopSpeaking() = runOnUiThread { try { tts?.stop() } catch (_: Exception) {} }
        @JavascriptInterface fun setRate(rate: Float) {
            prefs.edit().putFloat("rate", rate).apply(); tts?.setSpeechRate(rate)
        }
        @JavascriptInterface fun setPitch(p: Float) {
            prefs.edit().putFloat("pitch", p).apply(); tts?.setPitch(p)
        }
        /** "en-IN" or "ta-IN". Returns false if the phone has no voice for it. */
        @JavascriptInterface fun setLang(code: String): Boolean {
            prefs.edit().putString("lang", code).apply()
            return applyTtsLang(code)
        }

        // clipboard (Settings → Copy key)
        @JavascriptInterface
        fun copyText(text: String): Boolean = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Gemini key", text)); true
        } catch (_: Exception) { false }

        // storage
        @JavascriptInterface fun getPref(key: String): String = prefs.getString("p_$key", "") ?: ""
        @JavascriptInterface fun setPref(key: String, value: String) = prefs.edit().putString("p_$key", value).apply()

        // brain
        @JavascriptInterface
        fun ask(system: String, history: String, search: Boolean, cbId: String) = askInternal(system, history, search, false, cbId)

        @JavascriptInterface
        fun askImage(system: String, history: String, cbId: String) = askInternal(system, history, false, true, cbId)

        private fun askInternal(system: String, history: String, search: Boolean, withImage: Boolean, cbId: String) {
            Thread {
                val out = JSONObject()
                try {
                    val key = prefs.getString("p_apikey", "") ?: ""
                    if (key.isBlank()) throw GeminiError(0, "NO_KEY")
                    val chosen = prefs.getString("p_model", "") ?: ""
                    val preferred = chosen.ifBlank { prefs.getString("p_lastModel", "") ?: "" }
                    val img = if (withImage) lastImage else null
                    val res = Gemini.ask(key, preferred, system, history, search, img)
                    if (chosen.isBlank()) prefs.edit().putString("p_lastModel", res.model).apply()
                    out.put("text", res.text).put("model", res.model)
                } catch (e: GeminiError) {
                    out.put("error", e.message ?: "error").put("code", e.code)
                } catch (e: Exception) {
                    out.put("error", "NETWORK").put("code", -1)
                }
                cb("onReply", cbId, out)
            }.start()
        }

        // apps & media
        @JavascriptInterface fun openApp(name: String): String = openInstalledApp(name)
        @JavascriptInterface fun openUrl(url: String): Boolean = openView(url)
        @JavascriptInterface
        fun play(query: String, where: String): Boolean {
            val pkg = if (where == "spotify") "com.spotify.music" else "com.google.android.youtube"
            if (playOn(pkg, query)) return true
            val q = Uri.encode(query)
            return if (where == "spotify") openView("https://open.spotify.com/search/$q")
            else openView("https://www.youtube.com/results?search_query=$q")
        }

        // clock
        @JavascriptInterface fun flashlight(on: Boolean): Boolean = torch(on)
        @JavascriptInterface
        fun alarm(hour: Int, minute: Int, label: String): Boolean = launch(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
        @JavascriptInterface
        fun timer(seconds: Int, label: String): Boolean = launch(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })

        // reminders
        @JavascriptInterface
        fun remind(at: Double, text: String): Int {
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_GENERIC) }
            }
            return Reminders.add(this@MainActivity, at.toLong(), text)
        }
        @JavascriptInterface fun reminders(): String = Reminders.list(this@MainActivity).toString()
        @JavascriptInterface fun removeReminder(id: Int) = Reminders.remove(this@MainActivity, id)
        @JavascriptInterface fun clearReminders() = Reminders.clear(this@MainActivity)

        // calls & messages
        @JavascriptInterface
        fun dial(number: String): Boolean = launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        @JavascriptInterface fun call(number: String) = runOnUiThread { placeCall(number) }
        @JavascriptInterface fun contact(text: String, cbId: String) = contactLookup(text, cbId, false)
        @JavascriptInterface fun contactSplit(text: String, cbId: String) = contactLookup(text, cbId, true)

        private fun contactLookup(text: String, cbId: String, split: Boolean) {
            if (has(Manifest.permission.READ_CONTACTS)) {
                Thread { resolveContact(text, cbId, split) }.start()
            } else runOnUiThread {
                pendingContact = Triple(text, cbId, split)
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), RC_CONTACTS)
            }
        }

        /** Opens the chat with the message typed in. Paul taps send. */
        @JavascriptInterface
        fun whatsapp(number: String, text: String): Boolean {
            val url = "https://wa.me/${waNumber(number)}?text=${Uri.encode(text)}"
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                if (installed(pkg) && launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))) return true
            }
            return openView(url)
        }

        @JavascriptInterface
        fun sms(number: String, text: String): Boolean =
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text))

        // photos
        @JavascriptInterface fun photo(source: String, cbId: String) = runOnUiThread { takePhoto(source, cbId) }

        // phone controls
        @JavascriptInterface
        fun volume(action: String, value: Int): Int {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val s = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(s)
            try {
                when (action) {
                    "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
                    "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
                    "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "set" -> am.setStreamVolume(s, (max * value.coerceIn(0, 100) / 100.0).toInt(), AudioManager.FLAG_SHOW_UI)
                }
            } catch (_: Exception) {}
            return (am.getStreamVolume(s) * 100.0 / max).toInt()
        }

        @JavascriptInterface
        fun ringer(mode: String): String {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                am.ringerMode = when (mode) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                "ok"
            } catch (_: SecurityException) {
                openSpecial("dnd"); "needs"
            } catch (_: Exception) { "fail" }
        }

        /** 0-100. Returns "ok", or "needs" when Android wants Paul to allow "Modify system settings". */
        @JavascriptInterface
        fun brightness(action: String, value: Int): String {
            if (!Settings.System.canWrite(this@MainActivity)) { openSpecial("writeSettings"); return "needs" }
            return try {
                val cr = contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                val next = when (action) {
                    "up" -> cur + 50
                    "down" -> cur - 50
                    "auto" -> {
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                        return "ok"
                    }
                    else -> (value.coerceIn(0, 100) * 255 / 100)
                }.coerceIn(5, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
                "ok"
            } catch (_: Exception) { "fail" }
        }

        @JavascriptInterface
        fun battery(): String {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = if (Build.VERSION.SDK_INT >= 23) bm.isCharging else false
            return JSONObject().put("pct", pct).put("charging", charging).toString()
        }

        @JavascriptInterface fun openSettings(which: String) = openSpecial(which)
        @JavascriptInterface fun perms(): String = permsJson().toString()

        @JavascriptInterface
        fun requestPerm(which: String) = runOnUiThread {
            val list = when (which) {
                "mic" -> arrayOf(Manifest.permission.RECORD_AUDIO)
                "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
                "phone" -> arrayOf(Manifest.permission.CALL_PHONE)
                "notify" -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray<String>()
                else -> { openSpecial(which); emptyArray<String>() }
            }
            if (list.isNotEmpty()) {
                val allDenied = list.all { !has(it) && !shouldShowRequestPermissionRationale(it) } &&
                        prefs.getBoolean("asked_$which", false)
                prefs.edit().putBoolean("asked_$which", true).apply()
                // Android stops showing the prompt after two "Deny"s: send Paul to the app page instead
                if (allDenied) openSpecial("app") else requestPermissions(list, if (which == "mic") RC_MIC else RC_GENERIC)
            }
        }

        // background wake word
        @JavascriptInterface
        fun setBackground(on: Boolean): Boolean {
            prefs.edit().putString("p_bg", if (on) "1" else "0").apply()
            return try {
                if (on) {
                    if (!hasMic()) { runOnUiThread { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC) }; return false }
                    if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
                        runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_GENERIC) }
                    WakeService.start(this@MainActivity)
                } else WakeService.stop(this@MainActivity)
                true
            } catch (_: Exception) { false }
        }
        @JavascriptInterface fun backgroundRunning(): Boolean = WakeService.running

        // BOF scanner (same engine as Reno's BOF)
        @JavascriptInterface
        fun bof(cbId: String) {
            Thread {
                val out = JSONObject()
                val list = JSONArray()
                val prices = JSONArray()
                var errors = 0
                val tf = Market.timeframes.getValue("5m")
                for ((name, sym) in Market.symbols) {
                    try {
                        val s = Market.fetch(sym, tf.interval, tf.range)
                        val htf = try { Market.fetch(sym, tf.htf, tf.range) } catch (_: Exception) { null }
                        val an = Engine.analyze(s, htf)
                        val price = if (s.candles.isNotEmpty()) s.candles.last().c else s.price
                        prices.put(JSONObject().put("name", name).put("price", price).put("prev", an.prevClose).put("open", s.open))
                        val todayT = if (s.candles.isNotEmpty() && an.todayStart < s.candles.size) s.candles[an.todayStart].t else 0L
                        for (g in an.signals) {
                            if (g.t < todayT) continue
                            list.put(JSONObject().put("name", name).put("bull", g.bullish).put("level", g.level.name)
                                .put("score", g.score).put("entry", g.entry).put("stop", g.stop).put("target", g.target)
                                .put("t", g.t * 1000).put("open", s.open))
                        }
                    } catch (_: Exception) { errors++ }
                }
                out.put("signals", list).put("prices", prices).put("errors", errors)
                cb("onBof", cbId, out)
            }.start()
        }

        // laptop link (desktop echo.py on the same Wi-Fi)
        @JavascriptInterface
        fun laptop(path: String, body: String, cbId: String) {
            Thread {
                val out = JSONObject()
                try {
                    val host = (prefs.getString("p_laptopHost", "") ?: "").trim()
                    if (host.isEmpty()) throw Exception("NO_HOST")
                    val base = if (host.contains(":")) host else "$host:8765"
                    val c = URL("http://$base$path").openConnection() as HttpURLConnection
                    c.connectTimeout = 3500
                    c.readTimeout = 8000
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    out.put("code", code).put("body", text)
                } catch (e: Exception) {
                    out.put("code", -1).put("error", e.message ?: "offline")
                }
                cb("onLaptop", cbId, out)
            }.start()
        }
    }
}
