#include <algorithm>
#include <cstddef>
#include <vector>

namespace directbit {

// Placeholder-free linear SRC wrapper used when DAC lacks source-rate support.
// This keeps processing in native layer and avoids Android mixer resampling.
int resampleLinear(
    const float* in,
    int inFrames,
    int channels,
    int inRate,
    int outRate,
    std::vector<float>& out
) {
    if (!in || inFrames <= 0 || channels <= 0 || inRate <= 0 || outRate <= 0) return -1;
    if (inRate == outRate) {
        out.assign(in, in + (inFrames * channels));
        return inFrames;
    }

    const double ratio = static_cast<double>(outRate) / static_cast<double>(inRate);
    const int outFrames = std::max(1, static_cast<int>(inFrames * ratio));
    out.resize(outFrames * channels);

    for (int f = 0; f < outFrames; ++f) {
        const double src = static_cast<double>(f) / ratio;
        const int i0 = std::clamp(static_cast<int>(src), 0, inFrames - 1);
        const int i1 = std::min(i0 + 1, inFrames - 1);
        const float t = static_cast<float>(src - i0);
        for (int c = 0; c < channels; ++c) {
            const float a = in[i0 * channels + c];
            const float b = in[i1 * channels + c];
            out[f * channels + c] = a + ((b - a) * t);
        }
    }
    return outFrames;
}

}  // namespace directbit
