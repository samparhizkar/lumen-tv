package com.airplay.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the receiver over mDNS/DNS-SD using Android's built-in NsdManager,
 * matching what the Sony BRAVIA publishes (see SONY-SPEC.md):
 *   service `_airplay._tcp`, port 7000, with the TXT keys senders look for.
 *
 * The `features` / `pk` / `pi` values come from the protocol core once it is
 * wired; placeholders here keep the advert well-formed. We intentionally do NOT
 * advertise AirPlay-2 bits (buffered audio / PTP / HK) — unprivileged, classic
 * AirPlay only, same posture as the Sony's own TXT.
 */
class BonjourAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    private var baseName = "AirPlay"
    private var port = 0
    private var txt: Map<String, String> = emptyMap()

    fun register(deviceName: String, port: Int, txt: Map<String, String>) {
        this.baseName = deviceName
        this.port = port
        this.txt = txt
        tryRegister(deviceName, attempt = 0)
    }

    /**
     * Android 14's MdnsAdvertiser throws NameConflictException instead of
     * auto-renaming, so a generic instance name (e.g. "Android TV", already used
     * by the platform's own Cast advert) fails. We retry with a numeric suffix,
     * the same recovery the classic responder did for us.
     */
    private fun tryRegister(name: String, attempt: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_airplay._tcp."
            setPort(this@BonjourAdvertiser.port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "registered '${s.serviceName}'") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {
                Log.e(TAG, "register failed code=$code name='$name' attempt=$attempt")
                if (code == NsdManager.FAILURE_INTERNAL_ERROR && attempt < 5) {
                    tryRegister("$baseName (${attempt + 2})", attempt + 1)
                }
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) { Log.i(TAG, "unregistered") }
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) { Log.e(TAG, "unregister failed $code") }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        listener = l
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object { private const val TAG = "BonjourAdvertiser" }
}
