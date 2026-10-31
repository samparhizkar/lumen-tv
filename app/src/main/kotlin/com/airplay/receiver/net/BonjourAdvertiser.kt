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
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    private var baseName = "AirPlay"
    private var port = 0
    private var txt: Map<String, String> = emptyMap()

    fun register(deviceName: String, port: Int, txt: Map<String, String>) {
        this.baseName = deviceName
        this.port = port
        this.txt = txt
        tryRegister(deviceName, attempt = 0)
        registerRaop(deviceName)
    }

    /**
     * A classic AirTunes speaker, `<MAC>@<name> Speaker._raop._tcp`, for a Mac's
     * Music app. If a Mac sees `_airplay._tcp` under the same device id, it wraps
     * the audio key with FairPlay v2 (fpaeskey), which no open implementation can
     * unwrap, whatever the feature bits say. So the speaker gets its own id (first
     * octet ^ 0x04, mirrored in raop_set_dnssd for the Apple-Response) and no
     * `ft`/`pk`, and the Mac treats it as an AirPort-style speaker: RSA
     * (rsaaeskey, `et=0,1`), as with shairport-sync. Mirroring keeps `_airplay._tcp`.
     */
    private fun registerRaop(name: String) {
        val mac = txt["deviceid"]?.split(":")?.map { it.toInt(16) }?.takeIf { it.size == 6 }
            ?.mapIndexed { i, b -> if (i == 0) b xor 0x04 else b }
            ?.joinToString("") { "%02X".format(it) } ?: return
        val raopTxt = linkedMapOf(
            "txtvers" to "1", "ch" to "2", "cn" to "0,1", "et" to "0,1", "ek" to "1",
            "md" to "0,1,2", "da" to "true", "sv" to "false", "sr" to "44100", "ss" to "16",
            "tp" to "UDP", "vn" to "65537", "vs" to (txt["srcvers"] ?: "220.68"),
            "am" to (txt["model"] ?: "AppleTV3,2"), "sf" to (txt["flags"] ?: "0x4"),
            "pw" to "false",
        )
        val info = NsdServiceInfo().apply {
            serviceName = "$mac@$name Speaker"
            serviceType = "_raop._tcp."
            setPort(this@BonjourAdvertiser.port)
            raopTxt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "registered raop '${s.serviceName}'") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.e(TAG, "raop register failed code=$code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        listeners += l
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
        listeners += l
    }

    fun unregister() {
        listeners.forEach { runCatching { nsd.unregisterService(it) } }
        listeners.clear()
    }

    companion object { private const val TAG = "BonjourAdvertiser" }
}
