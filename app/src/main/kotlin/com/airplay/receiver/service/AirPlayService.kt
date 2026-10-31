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
import com.airplay.receiver.net.AirPlayCore
import com.airplay.receiver.net.BonjourAdvertiser

/**
 * Long-lived foreground service: owns the native AirPlay core and the mDNS
 * advert, so the receiver is discoverable whenever the dongle is on (the Sony
 * runs its equivalent as a persistent system service — we approximate with a
 * foreground service + boot receiver, the best an unprivileged app can do).
 *
 * Decrypted media from the core is handed to the MirrorActivity when it is in
 * front; the service holds the session and launches the activity on session
 * start.
 */
class AirPlayService : Service() {

    private lateinit var advertiser: BonjourAdvertiser
    private var core: AirPlayCore? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        advertiser = BonjourAdvertiser(this)

        val c = AirPlayCore(object : AirPlayCore.Callbacks {
            override fun onVideoConfig(codec: Int, width: Int, height: Int, csd0: ByteArray, csd1: ByteArray?) {
                MirrorActivityBridge.onVideoConfig(this@AirPlayService, codec, width, height, csd0, csd1)
            }
            override fun onVideoFrame(data: ByteArray, captureNs: Long, isIdr: Boolean) =
                MirrorActivityBridge.onVideoFrame(data, captureNs, isIdr)
            override fun onAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) =
                MirrorActivityBridge.onAudioConfig(format, sampleRate, channels, csd0)
            override fun onAudioPacket(data: ByteArray, ptsUs: Long) =
                MirrorActivityBridge.onAudioPacket(data, ptsUs)
            override fun onTimingSync(streamPtsUs: Long, localPlaybackUs: Long) =
                MirrorActivityBridge.onTimingSync(streamPtsUs, localPlaybackUs)
            override fun onSessionStart() {
                ReceiverState.setConnected("AirPlay", ReceiverState.Session.MIRRORING)
                MirrorActivityBridge.launch(this@AirPlayService)
            }
            override fun onSessionEnd() {
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
