#pragma once

void applySoftwareGain(void* data, int numFrames, int channels, int format, float gain);
void setGainFromPercent(int percent);
float getCurrentGainLinear();
