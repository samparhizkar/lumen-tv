// JNI bridge between the vendored UxPlay airplay core and AirPlayCore.kt.
//
// The C core runs the RTSP/HTTP server, pairing, FairPlay and RTP decrypt on its
// own threads, and invokes the raop_callbacks below. Each one marshals into the
// matching method on the Kotlin AirPlayCore instance, which forwards to the app's
// Callbacks (VideoPipeline / AudioPipeline). See native-core/README.md.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

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
jmethodID m_audioVolume = nullptr; // (F)V
jmethodID m_audioFlush = nullptr;  // ()V
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

/**
 * Maps the sender's capture clock (ntp_time_remote, the sender's own clock in
 * ns) onto CLOCK_MONOTONIC for pacing. The core's synced ntp_time_local stays 0
 * because its NTP timing exchange never completes with macOS senders, but
 * pacing needs only the sender's frame spacing plus a stable offset: the
 * smallest recent "arrival - capture" (the fastest frame = zero extra delay).
 * FramePacer adds the link's jitter headroom on top. The offset tightens at once
 * when a faster frame shows up, relaxes every few seconds to follow clock
 * drift, and re-anchors on discontinuities.
 */
struct CaptureClock {
    bool anchored = false;
    int64_t offset = 0;
    int64_t window_min = INT64_MAX;
    int64_t window_start = 0;

    int64_t to_mono(int64_t capture_ns, int64_t arrival_ns) {
        int64_t d = arrival_ns - capture_ns;
        if (!anchored || llabs(d - offset) > 1000000000LL) {   // first frame or clock jump
            anchored = true; offset = d; window_min = d; window_start = arrival_ns;
        }
        if (d < offset) offset = d;
        if (d < window_min) window_min = d;
        if (arrival_ns - window_start > 5000000000LL) {
            offset = window_min;
            window_min = INT64_MAX;
            window_start = arrival_ns;
        }
        return capture_ns + offset;
    }
};
CaptureClock g_capture_clock;   // touched only on the core's mirror thread

void cb_conn_init(void *cls) {
    g_capture_clock = CaptureClock();   // a new sender has an unrelated clock
    Env e; if (e.env && g_self) e.env->CallVoidMethod(g_self, m_sessionStart);
}
// --- ALAC (audio-only AirPlay) ----------------------------------------------
// Android has no ALAC decoder, so ALAC is decoded here (Apple's reference
// decoder) and handed up as 16-bit PCM. Created at the audio SETUP on the
// httpd thread, used on the core's audio thread: guarded by g_alac_mutex.
std::mutex g_alac_mutex;
ALACDecoder *g_alac = nullptr;
std::vector<uint8_t> g_alac_pcm;

/** (Re)creates the decoder for [frame_length]-sample frames; 0 destroys it. */
void alac_reset(uint32_t frame_length) {
    std::lock_guard<std::mutex> lock(g_alac_mutex);
    delete g_alac;
    g_alac = nullptr;
    if (frame_length == 0) return;
    // AirPlay's ALAC "magic cookie" is an ALACSpecificConfig in big-endian. It
    // is fixed for AirPlay (16-bit stereo 44.1 kHz, Apple's default tuning
    // pb/mb/kb = 40/10/14, maxRun 255); only the frame size varies.
    uint8_t cookie[24] = {0};
    auto be32 = [&](int at, uint32_t v) {
        cookie[at] = v >> 24; cookie[at + 1] = v >> 16; cookie[at + 2] = v >> 8; cookie[at + 3] = v;
    };
    be32(0, frame_length);
    cookie[5] = 16;                                   // bitDepth (compatibleVersion 0)
    cookie[6] = 40; cookie[7] = 10; cookie[8] = 14;   // pb, mb, kb
    cookie[9] = 2;                                    // channels
    cookie[11] = 255;                                 // maxRun (big-endian u16)
    be32(20, 44100);                                  // sampleRate (maxFrameBytes, avgBitRate 0)
    auto *dec = new ALACDecoder();
    if (dec->Init(cookie, sizeof(cookie)) != 0) {
        LOG("ALAC decoder init failed");
        delete dec;
        return;
    }
    g_alac = dec;
    g_alac_pcm.resize((size_t) frame_length * 2 /*ch*/ * 2 /*bytes*/);
}

void cb_conn_destroy(void *cls) {
    alac_reset(0);
    Env e; if (e.env && g_self) e.env->CallVoidMethod(g_self, m_sessionEnd);
}

int cb_video_set_codec_and_size(void *cls, video_codec_t codec,
                                unsigned short ws, unsigned short hs,
                                unsigned short xo, unsigned short yo,
                                unsigned short w, unsigned short h) {
    LOG("video codec=%d source=%ux%u display=%ux%u", (int) codec, ws, hs, w, h);
    Env e; if (!e.env || !g_self) return 0;
    jint c = (codec == VIDEO_CODEC_H265) ? 1 : 0;
    // csd (SPS/PPS/VPS) arrives inline with the first frames for AirPlay mirror;
    // pass empty config here and let the stream layer split it from the AU.
    jbyteArray empty = toBytes(e.env, nullptr, 0);
    e.env->CallVoidMethod(g_self, m_videoConfig, c, (jint) w, (jint) h, empty, nullptr);
    e.env->DeleteLocalRef(empty);
    return 0;
}

// Frame-spacing histogram buckets (ms): <12, 12-21 (one 60Hz frame), 21-29,
// 29-40 (two frames: one skipped), >=40. Only gaps under 200 ms count, so idle
// pauses (the sender stops sending when nothing changes) don't pollute it.
static int spacing_bucket(int64_t ns) {
    double ms = ns / 1e6;
    return ms < 12 ? 0 : ms < 21 ? 1 : ms < 29 ? 2 : ms < 40 ? 3 : 4;
}

void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    Env e; if (!e.env || !g_self || !data) return;
    struct timespec mono;
    clock_gettime(CLOCK_MONOTONIC, &mono);
    int64_t mono_ns = (int64_t) mono.tv_sec * 1000000000LL + mono.tv_nsec;

    // Every ~2 s: incoming fps, plus how far apart frames were *captured* on the
    // sender vs how far apart they *arrived* here. Gaps in capture spacing mean
    // the sender skipped frames; gaps only in arrival spacing mean the network
    // bunched them.
    static int frames = 0;
    static int64_t window_start = 0, prev_capture = 0, prev_arrival = 0;
    static int cap_hist[5] = {0}, arr_hist[5] = {0};
    int64_t capture_ns = (int64_t) data->ntp_time_remote;
    if (prev_capture && capture_ns > prev_capture && capture_ns - prev_capture < 200000000LL)
        cap_hist[spacing_bucket(capture_ns - prev_capture)]++;
    if (prev_arrival && mono_ns - prev_arrival < 200000000LL)
        arr_hist[spacing_bucket(mono_ns - prev_arrival)]++;
    prev_capture = capture_ns;
    prev_arrival = mono_ns;
    int64_t now_ms = mono_ns / 1000000;
    if (window_start == 0) window_start = now_ms;
    frames++;
    if (now_ms - window_start >= 2000) {
        LOG("incoming video: %.1f fps (%d frames / %lld ms), last AU %d bytes | "
            "capture gaps <12/1f/21-29/2f/>40ms: %d/%d/%d/%d/%d | arrival: %d/%d/%d/%d/%d",
            frames * 1000.0 / (now_ms - window_start), frames,
            (long long) (now_ms - window_start), data->data_len,
            cap_hist[0], cap_hist[1], cap_hist[2], cap_hist[3], cap_hist[4],
            arr_hist[0], arr_hist[1], arr_hist[2], arr_hist[3], arr_hist[4]);
        frames = 0; window_start = now_ms;
        memset(cap_hist, 0, sizeof(cap_hist));
        memset(arr_hist, 0, sizeof(arr_hist));
    }
    jbyteArray buf = toBytes(e.env, data->data, data->data_len);
    // Capture time on CLOCK_MONOTONIC, the System.nanoTime() domain MediaCodec
    // schedules releaseOutputBuffer() against. Without a capture timestamp,
    // fall back to arrival time (no pacing benefit, but never wrong).
    int64_t capture_mono_ns = capture_ns > 0
        ? g_capture_clock.to_mono(capture_ns, mono_ns)
        : mono_ns;
    e.env->CallVoidMethod(g_self, m_videoFrame, buf, (jlong) capture_mono_ns, JNI_FALSE);
    e.env->DeleteLocalRef(buf);
}

// Called at the audio stream's SETUP with what the sender will send. ct is the
// RAOP compression type: 2 = ALAC (audio-only AirPlay, e.g. Music), 8 = AAC-ELD
// (screen mirroring audio). Classic AirPlay audio is 44.1 kHz stereo.
void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                         bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    LOG("audio format: ct=%u spf=%u usingScreen=%d isMedia=%d audioFormat=0x%llx",
        (unsigned) *ct, (unsigned) *spf, (int) *usingScreen, (int) *isMedia,
        (unsigned long long) *audioFormat);
    jint format;   // AudioPipeline.FORMAT_*
    switch (*ct) {
        case 2: format = 0; break;   // ALAC: decoded here, delivered as PCM
        case 4: format = 1; break;   // AAC-LC
        case 8: format = 2; break;   // AAC-ELD
        default: format = -1; break;
    }
    alac_reset(*ct == 2 ? (*spf ? *spf : 352) : 0);
    Env e; if (!e.env || !g_self) return;
    e.env->CallVoidMethod(g_self, m_audioConfig, format, (jint) 44100, (jint) 2, (jbyteArray) nullptr);
}

void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    Env e; if (!e.env || !g_self || !data) return;
    jbyteArray buf;
    if (data->ct == 2) {
        // ALAC: decode to interleaved 16-bit PCM before handing up.
        std::lock_guard<std::mutex> lock(g_alac_mutex);
        if (!g_alac) return;
        BitBuffer bits;
        BitBufferInit(&bits, data->data, (uint32_t) data->data_len);
        uint32_t frames = 0;
        int32_t status = g_alac->Decode(&bits, g_alac_pcm.data(), g_alac->mConfig.frameLength, 2, &frames);
        // A wrong AES key shows up as failing decodes; log the first few.
        static int failures = 0;
        if ((status != 0 || frames == 0) && failures++ < 5) {
            LOG("ALAC decode failed: status %d, %d bytes", (int) status, data->data_len);
        }
        if (status != 0 || frames == 0) return;
        buf = toBytes(e.env, g_alac_pcm.data(), (int) (frames * 2 * 2));
    } else {
        buf = toBytes(e.env, data->data, data->data_len);
    }
    // Sender timestamp (ns on the sender's clock) as µs; ntp_time_local stays 0
    // because the core's NTP exchange never completes (see CaptureClock).
    e.env->CallVoidMethod(g_self, m_audioPacket, buf, (jlong) (data->ntp_time_remote / 1000));
    e.env->DeleteLocalRef(buf);
}

void cb_audio_set_volume(void *cls, float volume_db) {
    Env e; if (e.env && g_self) e.env->CallVoidMethod(g_self, m_audioVolume, (jfloat) volume_db);
}

void cb_audio_flush(void *cls) {
    Env e; if (e.env && g_self) e.env->CallVoidMethod(g_self, m_audioFlush);
}

// --- no-op stubs for the remaining callbacks ---------------------------------
// The core calls several of these without null-checking (e.g. /info calls
// audio_set_client_volume), so every pointer must be non-null.
void st_void(void *cls) {}
void st_video_reset(void *cls, reset_type_t r) {}
void st_conn_reset(void *cls, int reason) {}
double st_audio_client_volume(void *cls) { return 0.0; }
void st_audio_set_metadata(void *cls, const void *b, int l) {}
void st_audio_set_coverart(void *cls, const void *b, int l) {}
void st_audio_remote_control_id(void *cls, const char *d, const char *a) {}
void st_audio_set_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) {}
void st_mirror_video_running(void *cls, bool r) {}
void st_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    if (admit) *admit = true;   // accept every sender for now
}
void st_display_pin(void *cls, char *pin) {}
void st_register_client(void *cls, const char *id, const char *pk, const char *name) {}
bool st_check_register(void *cls, const char *pk) { return false; }   // not yet paired
const char *st_passwd(void *cls, int *len) { if (len) *len = 0; return nullptr; }
void st_export_dacp(void *cls, const char *ar, const char *id) {}
void st_on_video_play(void *cls, const char *loc, float pos) {}
void st_on_video_scrub(void *cls, float pos) {}
void st_on_video_rate(void *cls, float rate) {}
void st_on_video_stop(void *cls) {}
void st_on_video_acquire(void *cls, playback_info_t *pi) {}
float st_on_video_playlist_remove(void *cls) { return 0.0f; }
void st_get_custom_profile(void *cls, const char **p) { if (p) *p = nullptr; }

} // namespace

// --- JNI entry points -------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_samparhizkar_lumen_net_AirPlayCore_nativeStart(JNIEnv *env, jobject thiz,
                                                      jstring deviceName, jint port) {
    g_self = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    m_videoConfig = env->GetMethodID(cls, "nvVideoConfig", "(III[B[B)V");
    m_videoFrame = env->GetMethodID(cls, "nvVideoFrame", "([BJZ)V");
    m_audioConfig = env->GetMethodID(cls, "nvAudioConfig", "(III[B)V");
    m_audioPacket = env->GetMethodID(cls, "nvAudioPacket", "([BJ)V");
    m_audioVolume = env->GetMethodID(cls, "nvAudioVolume", "(F)V");
    m_audioFlush = env->GetMethodID(cls, "nvAudioFlush", "()V");
    m_sessionStart = env->GetMethodID(cls, "nvSessionStart", "()V");
    m_sessionEnd = env->GetMethodID(cls, "nvSessionEnd", "()V");

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    // Real callbacks we implement.
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.video_process = cb_video_process;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_process = cb_audio_process;
    // No-op stubs so the core never calls a null pointer.
    cbs.video_pause = st_void;
    cbs.video_resume = st_void;
    cbs.conn_feedback = st_void;
    cbs.conn_reset = st_conn_reset;
    cbs.video_reset = st_video_reset;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = st_void;
    cbs.audio_set_client_volume = st_audio_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_set_metadata = st_audio_set_metadata;
    cbs.audio_set_coverart = st_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = st_void;
    cbs.audio_remote_control_id = st_audio_remote_control_id;
    cbs.audio_set_progress = st_audio_set_progress;
    cbs.mirror_video_running = st_mirror_video_running;
    cbs.report_client_request = st_report_client_request;
    cbs.display_pin = st_display_pin;
    cbs.register_client = st_register_client;
    cbs.check_register = st_check_register;
    cbs.passwd = st_passwd;
    cbs.export_dacp = st_export_dacp;
    cbs.on_video_play = st_on_video_play;
    cbs.on_video_scrub = st_on_video_scrub;
    cbs.on_video_rate = st_on_video_rate;
    cbs.on_video_stop = st_on_video_stop;
    cbs.on_video_acquire_playback_info = st_on_video_acquire;
    cbs.on_video_playlist_remove = st_on_video_playlist_remove;
    cbs.get_custom_profile = st_get_custom_profile;

    g_raop = raop_init(&cbs);
    if (!g_raop) { LOG("raop_init failed"); return nullptr; }

    // The core asserts a log callback is set before it logs (httpd_start logs).
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);

    // Advertise 1080p60 to the sender. The core defaults maxFPS=30, which makes
    // macOS send only 30fps; bump it and pin the display size to 1080p.
    raop_set_plist(g_raop, "width", 1920);
    raop_set_plist(g_raop, "height", 1080);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", 60);

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
    if (g_dnssd) {
        raop_set_dnssd(g_raop, g_dnssd);
    } else {
        LOG("dnssd_init failed err=%d", dnssd_err);
    }

    unsigned short p = (unsigned short) port;
    raop_start_httpd(g_raop, &p);
    LOG("airplay core started on port %u", p);

    // Build the _airplay._tcp TXT record iOS needs to offer mirroring. The key
    // values come from the core itself (features, pk) so they match what it uses
    // during /pair-verify. Missing deviceid/features/pk is why iOS hid us.
    char deviceid[18];
    snprintf(deviceid, sizeof(deviceid), "%02X:%02X:%02X:%02X:%02X:%02X",
             (unsigned char) hw_addr[0], (unsigned char) hw_addr[1],
             (unsigned char) hw_addr[2], (unsigned char) hw_addr[3],
             (unsigned char) hw_addr[4], (unsigned char) hw_addr[5]);
    uint64_t feat = g_dnssd ? dnssd_get_airplay_features(g_dnssd) : 0;
    char features[40];
    snprintf(features, sizeof(features), "0x%X,0x%X",
             (uint32_t) (feat & 0xFFFFFFFF), (uint32_t) (feat >> 32));
    // Stable pi UUID derived from the device id.
    char pi[40];
    snprintf(pi, sizeof(pi), "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             (unsigned char) hw_addr[0], (unsigned char) hw_addr[1], (unsigned char) hw_addr[2],
             (unsigned char) hw_addr[3], (unsigned char) hw_addr[4], (unsigned char) hw_addr[5],
             0x41, 0x50, 0x6c, 0x61, (unsigned char) hw_addr[0], (unsigned char) hw_addr[1],
             (unsigned char) hw_addr[2], (unsigned char) hw_addr[3], (unsigned char) hw_addr[4],
             (unsigned char) hw_addr[5]);
    const char *pk = (g_dnssd && g_dnssd->pk) ? g_dnssd->pk : "";

    jclass mapCls = env->FindClass("java/util/HashMap");
    jobject map = env->NewObject(mapCls, env->GetMethodID(mapCls, "<init>", "()V"));
    jmethodID put = env->GetMethodID(mapCls, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    auto putkv = [&](const char *k, const char *v) {
        jstring jk = env->NewStringUTF(k);
        jstring jv = env->NewStringUTF(v);
        env->CallObjectMethod(map, put, jk, jv);
        env->DeleteLocalRef(jk); env->DeleteLocalRef(jv);
    };
    putkv("deviceid", deviceid);
    putkv("features", features);
    putkv("flags", "0x4");
    putkv("model", "AppleTV3,2");
    putkv("srcvers", "220.68");
    putkv("vv", "2");
    putkv("pw", "false");
    putkv("pi", pi);
    putkv("pk", pk);
    putkv("rsf", "0x0");
    putkv("acl", "0");
    return map;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_samparhizkar_lumen_net_AirPlayCore_nativeStop(JNIEnv *env, jobject thiz) {
    if (g_raop) { raop_stop_httpd(g_raop); raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = nullptr; }
    alac_reset(0);
    if (g_self) { env->DeleteGlobalRef(g_self); g_self = nullptr; }
}
