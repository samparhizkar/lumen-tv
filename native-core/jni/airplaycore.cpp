// JNI bridge between the vendored UxPlay airplay core and AirPlayCore.kt.
//
// The C core runs the RTSP/HTTP server, pairing, FairPlay and RTP decrypt on its
// own threads, and invokes the raop_callbacks below. Each one marshals into the
// matching method on the Kotlin AirPlayCore instance, which forwards to the app's
// Callbacks (VideoPipeline / AudioPipeline). See native-core/README.md.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>

extern "C" {
#include "raop.h"
#include "stream.h"
#include "dnssd.h"
}

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "airplaycore", __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jobject g_self = nullptr;          // global ref to the AirPlayCore instance
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;

// cached forwarding method IDs on AirPlayCore
jmethodID m_videoConfig = nullptr; // (III[B[B)V
jmethodID m_videoFrame = nullptr;  // ([BJZ)V
jmethodID m_audioConfig = nullptr; // (III[B)V
jmethodID m_audioPacket = nullptr; // ([BJ)V
jmethodID m_sessionStart = nullptr;
jmethodID m_sessionEnd = nullptr;

struct Env {
    JNIEnv *env = nullptr;
    bool attached = false;
    Env() {
        if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
            g_vm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
    }
    ~Env() { if (attached) g_vm->DetachCurrentThread(); }
};

jbyteArray toBytes(JNIEnv *env, const void *data, int len) {
    jbyteArray arr = env->NewByteArray(len);
    if (data && len > 0) env->SetByteArrayRegion(arr, 0, len, (const jbyte *) data);
    return arr;
}

// --- raop callbacks ---------------------------------------------------------

void cb_log(void *cls, int level, const char *msg) {
    __android_log_print(ANDROID_LOG_INFO, "raop", "%s", msg ? msg : "");
}

void cb_conn_init(void *cls) {
    Env e; if (e.env && g_self) e.env->CallVoidMethod(g_self, m_sessionStart);
}
void cb_conn_destroy(void *cls) {
    Env e; if (e.env && g_self) e.env->CallVoidMethod(g_self, m_sessionEnd);
}

int cb_video_set_codec_and_size(void *cls, video_codec_t codec,
                                unsigned short ws, unsigned short hs,
                                unsigned short xo, unsigned short yo,
                                unsigned short w, unsigned short h) {
    Env e; if (!e.env || !g_self) return 0;
    jint c = (codec == VIDEO_CODEC_H265) ? 1 : 0;
    // csd (SPS/PPS/VPS) arrives inline with the first frames for AirPlay mirror;
    // pass empty config here and let the stream layer split it from the AU.
    jbyteArray empty = toBytes(e.env, nullptr, 0);
    e.env->CallVoidMethod(g_self, m_videoConfig, c, (jint) w, (jint) h, empty, nullptr);
    e.env->DeleteLocalRef(empty);
    return 0;
}

void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    Env e; if (!e.env || !g_self || !data) return;
    jbyteArray buf = toBytes(e.env, data->data, data->data_len);
    // ntp_time_local is in NTP units (µs since epoch base); pass as pts.
    e.env->CallVoidMethod(g_self, m_videoFrame, buf,
                          (jlong) data->ntp_time_local, JNI_FALSE);
    e.env->DeleteLocalRef(buf);
}

void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                         bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    // Minimal: accept whatever the sender offers; detailed negotiation later.
}

void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    Env e; if (!e.env || !g_self || !data) return;
    jbyteArray buf = toBytes(e.env, data->data, data->data_len);
    e.env->CallVoidMethod(g_self, m_audioPacket, buf, (jlong) data->ntp_time_local);
    e.env->DeleteLocalRef(buf);
}

} // namespace

// --- JNI entry points -------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_airplay_receiver_net_AirPlayCore_nativeStart(JNIEnv *env, jobject thiz,
                                                      jstring deviceName, jint port) {
    g_self = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    m_videoConfig = env->GetMethodID(cls, "nvVideoConfig", "(III[B[B)V");
    m_videoFrame = env->GetMethodID(cls, "nvVideoFrame", "([BJZ)V");
    m_audioConfig = env->GetMethodID(cls, "nvAudioConfig", "(III[B)V");
    m_audioPacket = env->GetMethodID(cls, "nvAudioPacket", "([BJ)V");
    m_sessionStart = env->GetMethodID(cls, "nvSessionStart", "()V");
    m_sessionEnd = env->GetMethodID(cls, "nvSessionEnd", "()V");

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.video_process = cb_video_process;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_process = cb_audio_process;

    g_raop = raop_init(&cbs);
    if (!g_raop) { LOG("raop_init failed"); return nullptr; }

    // The core asserts a log callback is set before it logs (httpd_start logs).
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, 5 /*LOGGER_INFO*/);

    const char *dev = env->GetStringUTFChars(deviceName, nullptr);
    // keyfile "" (not NULL): the core derives a stable ed25519 identity from the
    // device_id. NULL would strlen()-crash. TODO: persist a real keyfile path in
    // app storage so the pairing identity survives a device rename.
    raop_init2(g_raop, 1 /*nohold*/, dev, "" /*keyfile*/);

    // dnssd holds the name / MAC / feature bits used in the /info response.
    // hw_addr is a stable 6-byte id; TODO surface the real Wi-Fi MAC from Kotlin.
    static const char hw_addr[6] = {0x02, 0x00, 0x00, 0x48, 0x4F, 0x4D};
    int dnssd_err = 0;
    g_dnssd = dnssd_init(dev, (int) strlen(dev), hw_addr, sizeof(hw_addr), 0, &dnssd_err);
    env->ReleaseStringUTFChars(deviceName, dev);
    if (g_dnssd) raop_set_dnssd(g_raop, g_dnssd);
    else LOG("dnssd_init failed err=%d", dnssd_err);

    unsigned short p = (unsigned short) port;
    raop_start_httpd(g_raop, &p);
    LOG("airplay core started on port %u", p);

    // Minimal TXT; refined later (NsdManager carries discovery for now).
    jclass mapCls = env->FindClass("java/util/HashMap");
    jobject map = env->NewObject(mapCls, env->GetMethodID(mapCls, "<init>", "()V"));
    return map;
}

extern "C" JNIEXPORT void JNICALL
Java_com_airplay_receiver_net_AirPlayCore_nativeStop(JNIEnv *env, jobject thiz) {
    if (g_raop) { raop_stop_httpd(g_raop); raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = nullptr; }
    if (g_self) { env->DeleteGlobalRef(g_self); g_self = nullptr; }
}
