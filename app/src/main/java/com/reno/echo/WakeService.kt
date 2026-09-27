package com.reno.echo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * "Hey Echo" with the screen off: a foreground service that keeps listening
 * for the wake word and pops Echo open when it hears it.
 * Pauses itself while the Echo screen is open (the screen does its own listening).
 */
class WakeService : Service() {

    companion object {
        const val CHANNEL = "wake"
        const val ALERT_CHANNEL = "wake_alert"
        @Volatile var running = false


        fun start(c: Context) {
            val i = Intent(c, WakeService::class.java)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WakeService::class.java))
        }

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, c.getString(R.string.app_name) + " wake word listener", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while listening for the wake word"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, c.getString(R.string.app_name) + " wake-ups", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tap to talk when you are heard"
                }
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var alive = false
    private var mutedByUs = false
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only runs when Paul switched it on in the app. Never restarts itself.
        if (intent?.action == "STOP" || getSharedPreferences("echo", MODE_PRIVATE).getString("p_bg", "0") != "1") {
            getSharedPreferences("echo", MODE_PRIVATE).edit().putString("p_bg", "0").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        channels(this)
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(11, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!alive) {
            alive = true
            running = true
            main.postDelayed({ loop() }, 800)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notif_listening))
            .setContentText(getString(R.string.notif_say))
            .setColor(getColor(R.color.brand))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun loop() {
        if (!alive) return
        if (MainActivity.visible || MainActivity.inCall(this)) {
            main.postDelayed({ loop() }, 1500)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            main.postDelayed({ loop() }, 10_000)
            return
        }
        try { rec?.destroy() } catch (_: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { main.postDelayed({ unmute() }, 350) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                unmute()
                val wait = when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 6000L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1500L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stopSelf(); return }
                    else -> 250L
                }
                main.postDelayed({ loop() }, wait)
            }
            override fun onResults(results: Bundle?) {
                unmute()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                val w = resources.getStringArray(R.array.wake_words).firstOrNull { text.contains(it) }
                if (w != null) {
                    val rest = text.substring(text.indexOf(w) + w.length).trim(' ', ',', '.', '!', '?')
                    wake(rest)
                    main.postDelayed({ loop() }, 4000)
                } else {
                    main.postDelayed({ loop() }, 150)
                }
            }
        })
        val lang = getSharedPreferences("echo", MODE_PRIVATE).getString("lang", "en-IN")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "ta-IN") "en-IN" else lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        muteBeep()
        try { r.startListening(i) } catch (_: Exception) { unmute(); main.postDelayed({ loop() }, 2000) }
    }

    /** The recognizer's start "beep" plays on the music stream on most phones. Hush it if nothing is playing. */
    private fun muteBeep() {
        try {
            if (!audio.isMusicActive) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                mutedByUs = true
            }
        } catch (_: Exception) {}
    }

    private fun unmute() {
        if (!mutedByUs) return
        mutedByUs = false
        try { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
    }

    private fun wake(command: String) {
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("fromWake", true)
        if (command.length > 2) i.putExtra("cmd", command)
        // Popping up from the background needs "Display over other apps".
        // Without it, a heads-up notification lets you tap straight in.
        var opened = false
        if (Settings.canDrawOverlays(this)) {
            try { startActivity(i); opened = true } catch (_: Exception) {}
        }
        if (!opened) {
            val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.notif_wake_title))
                .setContentText(if (command.length > 2) "Tap to run: $command" else getString(R.string.notif_tap))
                .setColor(getColor(R.color.brand))
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(20_000)
                .build()
            try { getSystemService(NotificationManager::class.java).notify(12, n) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        alive = false
        running = false
        main.removeCallbacksAndMessages(null)
        unmute()
        try { rec?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
