package com.airplay.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.airplay.receiver.HomeActivity
import com.airplay.receiver.Prefs
import com.airplay.receiver.ReceiverState
import com.airplay.receiver.media.AudioPipeline
import com.airplay.receiver.net.AirPlayCore
import com.airplay.receiver.net.BonjourAdvertiser

/**
 * Long-lived foreground service: owns the native AirPlay core and the mDNS
 * advert, so the receiver is discoverable whenever the dongle is on (the Sony
 * runs its equivalent as a persistent system service — we approximate with a
 * foreground service + boot receiver, the best an unprivileged app can do).
 *
 * Video goes to the MirrorActivity (opened when a sender starts mirroring);
 * audio is played right here, so audio-only AirPlay works with no screen.
 */
class AirPlayService : Service() {

    private lateinit var advertiser: BonjourAdvertiser
    private var core: AirPlayCore? = null
    private val audio = AudioPipeline()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        advertiser = BonjourAdvertiser(this)

        val c = AirPlayCore(object : AirPlayCore.Callbacks {
            override fun onVideoConfig(codec: Int, width: Int, height: Int, csd0: ByteArray, csd1: ByteArray?) {
                // Video means screen mirroring: only now open the mirror screen,
                // so an audio-only session never pops up a black screen.
                ReceiverState.setConnected("AirPlay", ReceiverState.Session.MIRRORING)
                MirrorActivityBridge.onVideoConfig(this@AirPlayService, codec, width, height, csd0, csd1)
            }
            override fun onVideoFrame(data: ByteArray, captureNs: Long, isIdr: Boolean) =
                MirrorActivityBridge.onVideoFrame(data, captureNs, isIdr)
            override fun onAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) {
                audio.configure(format, sampleRate, channels, csd0)
            }
            override fun onAudioPacket(data: ByteArray, ptsUs: Long) = audio.submit(data, ptsUs)
            override fun onAudioVolume(volumeDb: Float) = audio.setVolumeDb(volumeDb)
            override fun onAudioFlush() = audio.flush()
            override fun onSessionStart() {
                ReceiverState.setConnected("AirPlay", ReceiverState.Session.AUDIO)
            }
            override fun onSessionEnd() {
                audio.stop()
                ReceiverState.setReady(Prefs(this@AirPlayService).deviceName, HomeActivity.localIp())
                MirrorActivityBridge.finish()
            }
        })

        val name = Prefs(this).deviceName
        val txt = runCatching { c.nativeStart(name, PORT) }.getOrDefault(defaultTxt())
        advertiser.register(name, PORT, txt)
        core = c
        ReceiverState.setReady(name, HomeActivity.localIp())
        Log.i(TAG, "AirPlay service up on :$PORT as '$name'")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        advertiser.unregister()
        runCatching { core?.nativeStop() }
        audio.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun defaultTxt(): Map<String, String> = mapOf(
        "srcvers" to "377.25.05",
        "protovers" to "1.1",
        "model" to "HomaticsDongleR4K",
        "flags" to "0x4",
    )

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHAN, "AirPlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHAN)
            .setContentTitle("AirPlay Receiver")
            .setContentText("Ready to receive")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHAN = "airplay"
        private const val NOTIF_ID = 1
        const val PORT = 7000

        fun start(ctx: Context) {
            val i = Intent(ctx, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
