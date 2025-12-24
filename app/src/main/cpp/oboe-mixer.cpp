#include <oboe/Oboe.h>
#include <jni.h>
#include <cmath>
#include <atomic>
#include <random>
#include <pthread.h>
#include <unistd.h>
#include <sys/resource.h>
#include <android/log.h>

#define LOG_TAG "MixerEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace oboe;

// ==================== 配置参数 ====================
constexpr int kSampleRate = 48000;
constexpr int kBgmCycle   = kSampleRate;  // 1秒循环
constexpr float kBgmAmp   = 0.005f;
constexpr float kClickAmp = 0.9f;
constexpr int kClickDurMs = 50;
constexpr int kClickLen   = kSampleRate * kClickDurMs / 1000;

static std::atomic<bool> gClickPending{false};

// ==================== 音频引擎 ====================
class MixerEngine : public AudioStreamCallback {
public:
    MixerEngine() {
        std::random_device rd;
        mGen = std::mt19937(rd());
        mDis = std::uniform_real_distribution<float>(-1.0f, 1.0f);
        generateBgm();
        generateClick();
    }

    ~MixerEngine() {
        stop();
    }

    bool start() {
        AudioStreamBuilder builder;
        builder.setCallback(this)
                ->setChannelCount(ChannelCount::Mono)
                ->setSampleRate(kSampleRate)
                ->setFormat(AudioFormat::Float)
                ->setSharingMode(SharingMode::Exclusive)
                ->setPerformanceMode(PerformanceMode::LowLatency)
                ->setUsage(Usage::Game)
                ->setContentType(ContentType::Sonification)
                ->setAudioApi(AudioApi::AAudio);

        Result result = builder.openStream(mStream);
        if (result != Result::OK) {
            LOGE("Failed to open stream: %s", convertToText(result));
            return false;
        }

        int32_t burst = mStream->getFramesPerBurst();
        mStream->setBufferSizeInFrames(burst * 2);

        setRealTimePriority();
        return mStream->requestStart() == Result::OK;
    }

    void stop() {
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
    }

    void setBgmOn(bool on) { mBgmOn = on; }

    // ========== FIX 1: 流健康检查 ==========
    bool isStreamHealthy() const {
        return mStream != nullptr &&
               (mStream->getState() == StreamState::Open ||
                mStream->getState() == StreamState::Starting ||
                mStream->getState() == StreamState::Started);
    }

private:
    DataCallbackResult onAudioReady(AudioStream *stream, void *audioData, int32_t numFrames) override {
        if (numFrames <= 0 || audioData == nullptr) {
            LOGW("Invalid callback: numFrames=%d, data=%p", numFrames, audioData);
            return DataCallbackResult::Continue;
        }

        // FIX 2: 检测到断开时释放流
        if (stream->getState() == StreamState::Disconnected) {
            LOGE("Stream disconnected! Will trigger restart.");
            mStream.reset();
            return DataCallbackResult::Stop;
        }

        float* out = static_cast<float*>(audioData);
        if (gClickPending.exchange(false, std::memory_order_acquire)) {
            mClickIndex = 0;
            mClickActive = true;
        }

        for (int i = 0; i < numFrames; ++i) {
            float s = 0.0f;

            if (mBgmOn) {
                s += mBgm[mBgmIndex] * kBgmAmp;
                mBgmIndex = (mBgmIndex + 1) % kBgmCycle;
            }

            if (mClickActive) {
                if (mClickIndex < kClickLen) {
                    s += mClick[mClickIndex] * kClickAmp;
                    if (++mClickIndex >= kClickLen) {
                        mClickActive = false;
                    }
                } else {
                    mClickActive = false;
                }
            }

            out[i] = std::tanh(s * 0.8f);
        }

        return DataCallbackResult::Continue;
    }

    void setRealTimePriority() {
        pthread_t self = pthread_self();
        struct sched_param param = {.sched_priority = 50};
        if (pthread_setschedparam(self, SCHED_FIFO, &param) != 0) {
            LOGW("SCHED_FIFO failed, fallback to setpriority");
            setpriority(PRIO_PROCESS, gettid(), -20);
        }
    }

    void generateBgm() {
        for (int i = 0; i < kBgmCycle; ++i) {
            float sine = std::sin(2.0f * M_PI * 80.0f * i / kSampleRate);
            float noise = mDis(mGen);
            mBgm[i] = sine + 0.3f * noise;
        }
    }

    void generateClick() {
        for (int i = 0; i < kClickLen; ++i) {
            float env = std::exp(-i / 200.0f);
            mClick[i] = std::sin(2.0f * M_PI * 2000.0f * i / kSampleRate) * env;
        }
    }

    std::shared_ptr<AudioStream> mStream;
    float mBgm[kBgmCycle];
    float mClick[kClickLen];
    bool mBgmOn = true;
    int mBgmIndex = 0;
    int mClickIndex = 0;
    bool mClickActive = false;
    std::mt19937 mGen;
    std::uniform_real_distribution<float> mDis;
};

static std::unique_ptr<MixerEngine> gEngine;

// ==================== JNI接口 ====================
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_lowlatencygamedetection_tool_MixerEngine_nativeStart(JNIEnv *, jclass) {
    if (gEngine) {
        LOGW("Engine already started!");
        return false;
    }
    gEngine = std::make_unique<MixerEngine>();
    return gEngine->start();
}

JNIEXPORT void JNICALL
Java_com_lowlatencygamedetection_tool_MixerEngine_nativeStop(JNIEnv *, jclass) {
    if (gEngine) {
        gEngine->stop();
        gEngine.reset();
    }
}

JNIEXPORT void JNICALL
Java_com_lowlatencygamedetection_tool_MixerEngine_nativePlayClick(JNIEnv *, jclass) {
    gClickPending.store(true, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_lowlatencygamedetection_tool_MixerEngine_nativeSetBgmOn(JNIEnv *, jclass, jboolean on) {
    if (gEngine) gEngine->setBgmOn(on);
}

// ========== FIX 3: 健康检查JNI ==========
JNIEXPORT jboolean JNICALL
Java_com_lowlatencygamedetection_tool_MixerEngine_nativeIsStreamHealthy(JNIEnv *, jclass) {
    return gEngine ? gEngine->isStreamHealthy() : JNI_FALSE;
}

} // extern "C"