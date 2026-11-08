# Web encode vector (web sharing helper)

`web_noise48k_3s.ogg` (68,180 bytes, sha256 904c8177e4177f12ba917bd04c7a0ccce6677612780e09811addfced1e51fcf9):
what the web SDK (`@quran-lab/tajweedformer`, `web/sharing`) writes for this input, with libopus 1.6.1
compiled to WebAssembly (`scripts/build_opus.sh`):

- input: 3 s at 48 kHz, the Swift tests' noise (64-bit LCG `s = s * 6364136223846793005 + 1442695040888963407`
  from seed 1; sample = `(Float(s >> 40) / 2^24 * 2 - 1) * 0.3`, all in 32-bit float), pushed at once;
- encoder: PLATFORM_CLIENT.md 10.2 (AUDIO 2049, fullband, VBR unconstrained, complexity 10, 20 ms,
  192 kbit/s, signal auto, lsb depth 24, no FEC, no DTX); pre-skip 312;
- Ogg: serial 42, vendor `QuranLab SDK 2.0.0 libopus 1.6.1`, one file (cutting it with any segment plan
  gives the same bytes concatenated).

Checked: a native x86-64 build of the same libopus sources (MinGW gcc 13, -O2, plain C) gives the same
151 packets byte for byte as the wasm build. Not checked: Apple's NEON build (clang on arm64 may use
NEON kernels and FMA contraction, which can change the float encoder's choices); compare its packets
for this input with this file to know whether encoder bytes (not only the muxer, `ogg/`) agree across
SDKs. The muxer vector `ogg/` passes byte for byte through the web writer (test/sharing_vector.test.mjs).
