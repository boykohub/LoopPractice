// Native time-stretch engines for Music Practice.
//  Mode 0 "Melody": Signalsmith Stretch (MIT) - polyphonic, smooth on tonal material.
//  Mode 1 "Drums":  Rubber Band R2 (GPL) with crisp transients + percussive detector.
// Input/output: interleaved 16-bit PCM. Output length is exactly input / speed.

#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"
#include "rubberband/RubberBandStretcher.h"

namespace {

struct Engine {
    int ch;
    int sr;
    int mode;
    double speed = 1.0;
    double pitch = 1.0;

    std::unique_ptr<signalsmith::stretch::SignalsmithStretch<float>> ss;
    std::unique_ptr<RubberBand::RubberBandStretcher> rb;

    std::vector<std::vector<float>> in;   // per-channel input FIFO
    size_t inRead = 0;
    std::vector<int16_t> out;             // interleaved output FIFO
    size_t outRead = 0;

    std::vector<std::vector<float>> tmp;  // per-channel scratch output
    std::vector<float *> inPtr, outPtr;
    double frac = 0.0;
    long drop = 0;                        // output frames to discard (engine latency)

    Engine(int channels, int sampleRate, int m) : ch(channels), sr(sampleRate), mode(m) {
        in.resize(ch);
        tmp.resize(ch);
        inPtr.resize(ch);
        outPtr.resize(ch);
        if (mode == 1) {
            using RB = RubberBand::RubberBandStretcher;
            RB::Options opts = RB::OptionProcessRealTime | RB::OptionEngineFaster |
                               RB::OptionTransientsCrisp | RB::OptionDetectorPercussive |
                               RB::OptionPhaseLaminar | RB::OptionThreadingNever |
                               RB::OptionPitchHighConsistency | RB::OptionChannelsTogether;
            rb = std::make_unique<RB>((size_t) sr, (size_t) ch, opts, 1.0, 1.0);
        } else {
            ss = std::make_unique<signalsmith::stretch::SignalsmithStretch<float>>();
            ss->presetDefault(ch, (float) sr);
        }
        reset();
    }

    void setParams(double s, double p) {
        speed = std::max(0.05, s);
        pitch = std::max(0.1, p);
        if (rb) {
            rb->setTimeRatio(1.0 / speed);
            rb->setPitchScale(pitch);
        } else {
            ss->setTransposeFactor((float) pitch);
        }
    }

    void reset() {
        for (auto &c : in) c.clear();
        inRead = 0;
        out.clear();
        outRead = 0;
        frac = 0.0;
        if (rb) {
            rb->reset();
            rb->setTimeRatio(1.0 / speed);
            rb->setPitchScale(pitch);
            // Prime with silence so the first real sample comes out right away.
            size_t pad = rb->getPreferredStartPad();
            if (pad > 0) {
                std::vector<std::vector<float>> z(ch, std::vector<float>(pad, 0.f));
                for (int c = 0; c < ch; ++c) inPtr[c] = z[c].data();
                rb->process(inPtr.data(), pad, false);
            }
            drop = (long) rb->getStartDelay();
        } else {
            ss->reset();
            ss->setTransposeFactor((float) pitch);
            drop = (long) ss->outputLatency() + (long) std::lround(ss->inputLatency() / speed);
        }
    }

    size_t inAvail() const { return in[0].size() - inRead; }

    void ensureTmp(size_t n) {
        for (auto &c : tmp) if (c.size() < n) c.resize(n);
        for (int c = 0; c < ch; ++c) outPtr[c] = tmp[c].data();
    }

    void emit(size_t n) {
        out.reserve(out.size() + n * ch);
        for (size_t i = 0; i < n; ++i) {
            if (drop > 0) { --drop; continue; }
            for (int c = 0; c < ch; ++c) {
                float v = tmp[c][i] * 32767.f;
                v = std::min(32767.f, std::max(-32768.f, v));
                out.push_back((int16_t) std::lrint(v));
            }
        }
    }

    void compactInput() {
        if (inRead > 65536) {
            for (auto &c : in) c.erase(c.begin(), c.begin() + (long) inRead);
            inRead = 0;
        }
    }

    // --- Signalsmith ---
    void ssStep(int n) {
        double exact = n / speed + frac;
        int outN = (int) std::floor(exact);
        frac = exact - outN;
        ensureTmp((size_t) std::max(outN, 1));
        for (int c = 0; c < ch; ++c) inPtr[c] = in[c].data() + inRead;
        ss->process(inPtr.data(), n, outPtr.data(), outN);
        inRead += (size_t) n;
        emit((size_t) outN);
    }

    // --- Rubber Band ---
    void rbRetrieve() {
        int av;
        while ((av = rb->available()) > 0) {
            ensureTmp((size_t) av);
            size_t got = rb->retrieve(outPtr.data(), (size_t) av);
            if (got == 0) break;
            emit(got);
        }
    }

    void rbFeed(size_t n, bool final) {
        for (int c = 0; c < ch; ++c) inPtr[c] = in[c].data() + inRead;
        rb->process(inPtr.data(), n, final);
        inRead += n;
        rbRetrieve();
    }

    void process() {
        if (rb) {
            const size_t chunk = 1024;
            while (inAvail() >= chunk) rbFeed(chunk, false);
        } else {
            const int interval = std::max(1, ss->intervalSamples());
            while (inAvail() >= (size_t) interval) ssStep(interval);
        }
        compactInput();
    }

    void queue(const int16_t *pcm, int frames) {
        for (int c = 0; c < ch; ++c) {
            auto &v = in[c];
            size_t base = v.size();
            v.resize(base + (size_t) frames);
            for (int i = 0; i < frames; ++i) v[base + i] = pcm[i * ch + c] * (1.f / 32768.f);
        }
        process();
    }

    void endOfStream() {
        if (rb) {
            rbFeed(inAvail(), true);
            rbRetrieve();
        } else {
            size_t rest = inAvail();
            if (rest > 0) ssStep((int) rest);
            int tail = ss->outputLatency() + (int) std::lround(ss->inputLatency() / speed);
            if (tail > 0) {
                ensureTmp((size_t) tail);
                ss->flush(outPtr.data(), tail);
                emit((size_t) tail);
            }
        }
        compactInput();
    }

    int available() const { return (int) ((out.size() - outRead) / (size_t) ch); }

    int read(int16_t *dst, int maxFrames) {
        int n = std::min(maxFrames, available());
        size_t count = (size_t) n * ch;
        std::copy(out.begin() + (long) outRead, out.begin() + (long) (outRead + count), dst);
        outRead += count;
        if (outRead >= out.size()) {
            out.clear();
            outRead = 0;
        } else if (outRead > 262144) {
            out.erase(out.begin(), out.begin() + (long) outRead);
            outRead = 0;
        }
        return n;
    }
};

inline Engine *E(jlong h) { return reinterpret_cast<Engine *>(h); }

} // namespace

#define JNI_FN(name) Java_com_boykohub_looppractice_NativeStretch_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nCreate)(JNIEnv *, jclass, jint channels, jint sampleRate, jint mode) {
    return reinterpret_cast<jlong>(new Engine(channels, sampleRate, mode));
}

JNIEXPORT void JNICALL JNI_FN(nDestroy)(JNIEnv *, jclass, jlong h) {
    delete E(h);
}

JNIEXPORT void JNICALL JNI_FN(nSetParams)(JNIEnv *, jclass, jlong h, jfloat speed, jfloat pitch) {
    E(h)->setParams(speed, pitch);
}

JNIEXPORT void JNICALL JNI_FN(nQueue)(JNIEnv *env, jclass, jlong h, jobject buf, jint frames) {
    auto *p = static_cast<int16_t *>(env->GetDirectBufferAddress(buf));
    if (p && frames > 0) E(h)->queue(p, frames);
}

JNIEXPORT jint JNICALL JNI_FN(nAvailable)(JNIEnv *, jclass, jlong h) {
    return E(h)->available();
}

JNIEXPORT jint JNICALL JNI_FN(nRead)(JNIEnv *env, jclass, jlong h, jobject buf, jint maxFrames) {
    auto *p = static_cast<int16_t *>(env->GetDirectBufferAddress(buf));
    if (!p) return 0;
    return E(h)->read(p, maxFrames);
}

JNIEXPORT void JNICALL JNI_FN(nEndOfStream)(JNIEnv *, jclass, jlong h) {
    E(h)->endOfStream();
}

JNIEXPORT void JNICALL JNI_FN(nReset)(JNIEnv *, jclass, jlong h) {
    E(h)->reset();
}

} // extern "C"
