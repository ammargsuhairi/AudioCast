package com.example.audiocast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast

class CastService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var clientCount = 0
        @Volatile var micMode = false
        const val PORT = 8080
        const val RATE = 44100
        private const val CHANNEL_ID = "audiocast"
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var server: StreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY

        val mic = intent.getBooleanExtra("mic", false)
        micMode = mic

        if (mic) {
            startAsForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            try {
                startMic()
                running = true
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal mulai: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            startProjectionCapture(code, data)
            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mulai: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(type: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AudioCast", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioCast aktif")
            .setContentText(if (micMode) "Suara mic dikirim ke iPhone" else "Audio Android lagi dikirim ke iPhone")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n, type)
    }

    /** Mode 1: tangkep audio internal (media, game, unknown). */
    private fun startProjectionCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .build()

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        record = rec
        stream(rec, 2)
    }

    /** Mode 2: rekam mic, dengan noise suppression dan AGC. Echo canceller sengaja tidak dipasang. */
    private fun startMic() {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, 16384))
            .build()
        record = rec

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
        } else {
            Toast.makeText(this, "HP ini nggak punya noise suppressor bawaan", Toast.LENGTH_LONG).show()
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true }
        }
        stream(rec, 1)
    }

    private fun stream(rec: AudioRecord, channels: Int) {
        val srv = StreamServer(applicationContext, PORT, RATE, channels)
        srv.start()
        server = srv

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiocast:cast")
            .apply { acquire() }

        val align = if (channels == 1) 1 else 3
        capturing = true
        Thread({
            val buf = ByteArray(4096)
            rec.startRecording()
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val len = n and align.inv()
                    if (len > 0) srv.broadcast(buf.copyOf(len))
                } else if (n < 0) {
                    break
                }
            }
        }, "capture").start()
    }

    override fun onDestroy() {
        capturing = false
        running = false
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        agc = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        server?.stop()
        server = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        clientCount = 0
        super.onDestroy()
    }
}
