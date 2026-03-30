#include <cmath>
#include <cstdint>
#include <vector>

namespace directbit {

struct EqBand {
    double gainDb = 0.0;
    double centerFreqHz = 1000.0;
    double qFactor = 0.707;
};

static EqBand gEq[10];
static bool gUpsamplingEnabled = false;
static int gUpsampleTargetRate = 192000;

void setEqBand(int idx, double gainDb, double centerFreqHz, double qFactor) {
    if (idx < 0 || idx >= 10) return;
    gEq[idx].gainDb = gainDb;
    gEq[idx].centerFreqHz = centerFreqHz;
    gEq[idx].qFactor = qFactor;
}

void setUpsampling(bool enabled, int targetRate) {
    gUpsamplingEnabled = enabled;
    if (targetRate > 0) gUpsampleTargetRate = targetRate;
}

// Lightweight 64-bit processing bus used by JNI glue.
void processPcm64(std::vector<double>& samples, double replayGain, double userGain, bool crossfade, double crossfadeGain) {
    const double rg = replayGain;
    const double ug = userGain;
    for (double& s : samples) {
        double y = s * rg * ug;
        if (crossfade) y *= crossfadeGain;
        y = std::tanh(y);
        s = y;
    }
}

}  // namespace directbit
