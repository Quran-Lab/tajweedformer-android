/* JNI over libopus for ai.quranlab.tajweedformer.sharing.OpusNative: one encoder or decoder per
 * handle (a pointer as a jlong), the ctl calls as one-value wrappers (opus_encoder_ctl is variadic).
 * Errors come back as negative libopus codes; nothing here throws. */
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(name) Java_ai_quranlab_tajweedformer_sharing_OpusNative_##name

static OpusEncoder *enc_of(jlong h) { return (OpusEncoder *)(intptr_t)h; }
static OpusDecoder *dec_of(jlong h) { return (OpusDecoder *)(intptr_t)h; }

JNIEXPORT jstring JNICALL FN(version)(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, opus_get_version_string());
}

/* out[0] = the handle (0 on failure), returns the libopus error code */
JNIEXPORT jint JNICALL FN(encoderCreate)(JNIEnv *env, jclass cls, jint rate, jint channels, jint application, jlongArray out) {
    (void)cls;
    int err = 0;
    OpusEncoder *e = opus_encoder_create(rate, channels, application, &err);
    jlong h = (err == OPUS_OK && e) ? (jlong)(intptr_t)e : 0;
    (*env)->SetLongArrayRegion(env, out, 0, 1, &h);
    return err;
}

JNIEXPORT jint JNICALL FN(encoderSet)(JNIEnv *env, jclass cls, jlong h, jint request, jint value) {
    (void)env; (void)cls;
    switch (request) {
    case OPUS_SET_BITRATE_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_BITRATE(value));
    case OPUS_SET_COMPLEXITY_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_COMPLEXITY(value));
    case OPUS_SET_VBR_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_VBR(value));
    case OPUS_SET_VBR_CONSTRAINT_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_VBR_CONSTRAINT(value));
    case OPUS_SET_BANDWIDTH_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_BANDWIDTH(value));
    case OPUS_SET_SIGNAL_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_SIGNAL(value));
    case OPUS_SET_LSB_DEPTH_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_LSB_DEPTH(value));
    case OPUS_SET_INBAND_FEC_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_INBAND_FEC(value));
    case OPUS_SET_DTX_REQUEST: return opus_encoder_ctl(enc_of(h), OPUS_SET_DTX(value));
    default: return OPUS_UNIMPLEMENTED;
    }
}

/* out[0] = the value; returns the libopus error code */
JNIEXPORT jint JNICALL FN(encoderGet)(JNIEnv *env, jclass cls, jlong h, jint request, jintArray out) {
    (void)cls;
    opus_int32 v = 0;
    int r;
    switch (request) {
    case OPUS_GET_BITRATE_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_BITRATE(&v)); break;
    case OPUS_GET_COMPLEXITY_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_COMPLEXITY(&v)); break;
    case OPUS_GET_VBR_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_VBR(&v)); break;
    case OPUS_GET_VBR_CONSTRAINT_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_VBR_CONSTRAINT(&v)); break;
    case OPUS_GET_BANDWIDTH_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_BANDWIDTH(&v)); break;
    case OPUS_GET_SIGNAL_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_SIGNAL(&v)); break;
    case OPUS_GET_LSB_DEPTH_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_LSB_DEPTH(&v)); break;
    case OPUS_GET_INBAND_FEC_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_INBAND_FEC(&v)); break;
    case OPUS_GET_DTX_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_DTX(&v)); break;
    case OPUS_GET_LOOKAHEAD_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_LOOKAHEAD(&v)); break;
    case OPUS_GET_SAMPLE_RATE_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_SAMPLE_RATE(&v)); break;
    case OPUS_GET_APPLICATION_REQUEST: r = opus_encoder_ctl(enc_of(h), OPUS_GET_APPLICATION(&v)); break;
    default: r = OPUS_UNIMPLEMENTED;
    }
    jint jv = (jint)v;
    (*env)->SetIntArrayRegion(env, out, 0, 1, &jv);
    return r;
}

/* Encodes frameSize samples per channel from pcm[offset...]; returns the packet length or an error. */
JNIEXPORT jint JNICALL FN(encodeFloat)(JNIEnv *env, jclass cls, jlong h, jfloatArray pcm, jint offset, jint frameSize, jbyteArray out) {
    (void)cls;
    jsize cap = (*env)->GetArrayLength(env, out);
    jfloat *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    int n = OPUS_ALLOC_FAIL;
    if (in && o) n = opus_encode_float(enc_of(h), in + offset, frameSize, (unsigned char *)o, cap);
    if (o) (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    if (in) (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL FN(encoderDestroy)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    if (h) opus_encoder_destroy(enc_of(h));
}

JNIEXPORT jint JNICALL FN(decoderCreate)(JNIEnv *env, jclass cls, jint rate, jint channels, jlongArray out) {
    (void)cls;
    int err = 0;
    OpusDecoder *d = opus_decoder_create(rate, channels, &err);
    jlong h = (err == OPUS_OK && d) ? (jlong)(intptr_t)d : 0;
    (*env)->SetLongArrayRegion(env, out, 0, 1, &h);
    return err;
}

/* Decodes one packet into pcm (at most maxFrames per channel); returns the samples per channel or an error. */
JNIEXPORT jint JNICALL FN(decodeFloat)(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jint length, jfloatArray pcm, jint maxFrames) {
    (void)cls;
    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    jfloat *o = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    int n = OPUS_ALLOC_FAIL;
    if (in && o) n = opus_decode_float(dec_of(h), (const unsigned char *)in, length, o, maxFrames, 0);
    if (o) (*env)->ReleasePrimitiveArrayCritical(env, pcm, o, 0);
    if (in) (*env)->ReleasePrimitiveArrayCritical(env, data, in, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL FN(decoderDestroy)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    if (h) opus_decoder_destroy(dec_of(h));
}
