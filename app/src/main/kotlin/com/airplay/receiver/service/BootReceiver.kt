package com.airplay.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Start the receiver service at boot so the dongle is always discoverable. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AirPlayService.start(context)
    }
}
