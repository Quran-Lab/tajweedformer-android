# Changelog

## 2.0.0 (first public release)

- TajweedFormer V4: `TajweedModel` opens a signed `sdk/v4.0` folder (signature, `min_sdk_version`,
  every file's size and SHA-256); `TajweedRecognizer` streams audio through the exact engine and
  keeps every head of the network; `finishAndScore` returns per-site tajweed verdicts with their
  evidence.
- Tajweed settings in three layers (app code, the portal's block, `calibration.json`), identical in
  every TajweedFormer SDK.
- Bit-exact: the 35 conformance clips give the engine's expected outputs, every output of every
  chunk, through this SDK's own API.
- Maven `ai.quranlab:tajweedformer`, package `ai.quranlab.tajweedformer`.
