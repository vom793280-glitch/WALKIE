#include <jni.h>
#include <opus.h>
#include <android/log.h>

#define TAG "PRIVATE_RADIO_OPUS"

#define LOGD(...) \
    __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define LOGE(...) \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


/*
 * ============================================================
 * Opus Encoder
 * ============================================================
 */

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_walkie_OpusEncoder_nativeCreate(
        JNIEnv* env,
        jobject thiz) {

    (void) env;
    (void) thiz;

    int error = OPUS_OK;

    OpusEncoder* encoder =
            opus_encoder_create(
                    16000,
                    1,
                    OPUS_APPLICATION_VOIP,
                    &error
            );

    if (
            error != OPUS_OK ||
                    encoder == nullptr
            ) {

        LOGE(
                "Opus Encoder 创建失败: %d",
                error
        );

        return 0;
    }


    /*
     * ========================================================
     * 24 kbps
     * ========================================================
     */

    opus_encoder_ctl(
            encoder,
            OPUS_SET_BITRATE(24000)
    );


    /*
     * ========================================================
     * 开启 FEC
     * ========================================================
     */

    opus_encoder_ctl(
            encoder,
            OPUS_SET_INBAND_FEC(1)
    );


    /*
     * ========================================================
     * 假设网络丢包率 5%
     * ========================================================
     */

    opus_encoder_ctl(
            encoder,
            OPUS_SET_PACKET_LOSS_PERC(5)
    );


    LOGD(
            "Opus Encoder 创建成功"
    );


    return reinterpret_cast<jlong>(
            encoder
    );
}


/*
 * ============================================================
 * Opus Encoder Encode
 * ============================================================
 */

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_walkie_OpusEncoder_nativeEncode(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jshortArray pcm) {

    (void) thiz;

    if (
            handle == 0 ||
                    pcm == nullptr
            ) {

        LOGE(
                "nativeEncode 参数无效"
        );

        return nullptr;
    }


    OpusEncoder* encoder =
            reinterpret_cast<OpusEncoder*>(
                    handle
            );
    LOGE(
            "encoder handle=%lld",
            (long long)handle
    );


    if(encoder == nullptr){

        LOGE(
                "Opus Encoder handle为空"
        );

        return nullptr;
    }


    jsize pcmSize =
            env->GetArrayLength(
                    pcm
            );


    /*
     * 16kHz / Mono / 20ms
     *
     * 16000 × 0.02 = 320 samples
     */

    if (
            pcmSize != 320
            ) {

        LOGE(
                "PCM 帧大小错误: %d",
                pcmSize
        );

        return nullptr;
    }


    jshort* pcmData =
            env->GetShortArrayElements(
                    pcm,
                    nullptr
            );


    if (
            pcmData == nullptr
            ) {

        LOGE(
                "无法获取 PCM 数据"
        );

        return nullptr;
    }


    /*
     * Opus 最大输出缓冲区
     */

    unsigned char output[4000];


    LOGE(
            "开始opus_encode handle=%lld pcmSize=%d",
            (long long)handle,
            pcmSize
    );


    int encodedBytes =
            opus_encode(
                    encoder,
                    reinterpret_cast<const opus_int16*>(
                            pcmData
                    ),
                    320,
                    output,
                    sizeof(output)
            );


    LOGE(
            "opus_encode返回=%d",
            encodedBytes
    );


    env->ReleaseShortArrayElements(
            pcm,
            pcmData,
            JNI_ABORT
    );


    if (
            encodedBytes < 0
            ) {

        LOGE(
                "opus_encode() 失败: %d",
                encodedBytes
        );

        return nullptr;
    }


    jbyteArray result =
            env->NewByteArray(
                    encodedBytes
            );


    if (
            result == nullptr
            ) {

        LOGE(
                "创建 ByteArray 失败"
        );

        return nullptr;
    }


    env->SetByteArrayRegion(
            result,
            0,
            encodedBytes,
            reinterpret_cast<const jbyte*>(
                    output
            )
    );


    LOGD(
            "Opus 编码成功: PCM=%d samples, Opus=%d bytes",
            pcmSize,
            encodedBytes
    );


    return result;
}


/*
 * ============================================================
 * Opus Encoder Destroy
 * ============================================================
 */

extern "C"
JNIEXPORT void JNICALL
Java_com_example_walkie_OpusEncoder_nativeDestroy(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {

    (void) env;
    (void) thiz;

    if (
            handle == 0
            ) {

        return;
    }


    OpusEncoder* encoder =
            reinterpret_cast<OpusEncoder*>(
                    handle
            );


    opus_encoder_destroy(
            encoder
    );


    LOGD(
            "Opus Encoder 已释放"
    );
}


/*
 * ============================================================
 * Opus Decoder Create
 *
 * Kotlin:
 *
 * nativeCreate(
 *     sampleRate: Int,
 *     channels: Int
 * ): Long
 *
 * ============================================================
 */

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_walkie_OpusDecoder_nativeCreate(
        JNIEnv* env,
        jobject thiz,
        jint sampleRate,
        jint channels) {

    (void) env;
    (void) thiz;


    /*
     * ========================================================
     * 参数检查
     * ========================================================
     */

    if (
            sampleRate <= 0 ||
                    channels <= 0
            ) {

        LOGE(
                "Opus Decoder 参数无效: sampleRate=%d channels=%d",
                sampleRate,
                channels
        );

        return 0;
    }


    /*
     * Opus Decoder 目前只支持 Mono
     */

    if (
            channels != 1
            ) {

        LOGE(
                "目前只支持 Mono，channels=%d",
                channels
        );

        return 0;
    }


    int error = OPUS_OK;


    OpusDecoder* decoder =
            opus_decoder_create(
                    sampleRate,
                    channels,
                    &error
            );


    if (
            error != OPUS_OK ||
                    decoder == nullptr
            ) {

        LOGE(
                "Opus Decoder 创建失败: %d",
                error
        );

        return 0;
    }


    LOGD(
            "Opus Decoder 创建成功: sampleRate=%d channels=%d",
            sampleRate,
            channels
    );


    return reinterpret_cast<jlong>(
            decoder
    );
}


/*
 * ============================================================
 * Opus Decoder Decode
 *
 * Kotlin:
 *
 * nativeDecode(
 *     handle: Long,
 *     opusData: ByteArray,
 *     opusLength: Int,
 *     pcmOutput: ShortArray
 * ): Int
 *
 * ============================================================
 */

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_walkie_OpusDecoder_nativeDecode(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jbyteArray opusData,
        jint opusLength,
        jshortArray pcmOutput) {

    (void) thiz;


    /*
     * ========================================================
     * 参数检查
     * ========================================================
     */

    if (
            handle == 0 ||
                    opusData == nullptr ||
                    pcmOutput == nullptr
            ) {

        LOGE(
                "nativeDecode 参数无效"
        );

        return -1;
    }


    if (
            opusLength <= 0
            ) {

        LOGE(
                "opusLength 无效: %d",
                opusLength
        );

        return -1;
    }


    OpusDecoder* decoder =
            reinterpret_cast<OpusDecoder*>(
                    handle
            );


    /*
     * ========================================================
     * 检查 Opus 数据长度
     * ========================================================
     */

    jsize actualOpusSize =
            env->GetArrayLength(
                    opusData
            );


    if (
            opusLength > actualOpusSize
            ) {

        LOGE(
                "opusLength 超出 ByteArray: %d > %d",
                opusLength,
                actualOpusSize
        );

        return -1;
    }


    /*
     * ========================================================
     * PCM 输出数组大小
     * ========================================================
     */

    jsize pcmCapacity =
            env->GetArrayLength(
                    pcmOutput
            );


    if (
            pcmCapacity <= 0
            ) {

        LOGE(
                "PCM 输出数组为空"
        );

        return -1;
    }


    /*
     * ========================================================
     * 获取 Opus 数据
     * ========================================================
     */

    jbyte* input =
            env->GetByteArrayElements(
                    opusData,
                    nullptr
            );


    if (
            input == nullptr
            ) {

        LOGE(
                "无法获取 Opus 数据"
        );

        return -1;
    }


    /*
     * ========================================================
     * 获取 PCM 输出数组
     * ========================================================
     */

    jshort* pcmOutputData =
            env->GetShortArrayElements(
                    pcmOutput,
                    nullptr
            );


    if (
            pcmOutputData == nullptr
            ) {

        env->ReleaseByteArrayElements(
                opusData,
                input,
                JNI_ABORT
        );

        LOGE(
                "无法获取 PCM 输出数组"
        );

        return -1;
    }


    /*
     * ========================================================
     * Opus Decode
     *
     * pcmOutput 当前 Kotlin 分配：
     *
     * ShortArray(320)
     *
     * 所以这里最多输出 320 samples。
     * ========================================================
     */

    int decodedSamples =
            opus_decode(
                    decoder,

                    reinterpret_cast<const unsigned char*>(
                            input
                    ),

                    opusLength,

                    reinterpret_cast<opus_int16*>(
                            pcmOutputData
                    ),

                    pcmCapacity,

                    0
            );


    /*
     * ========================================================
     * 释放 JNI 数组
     * ========================================================
     */

    env->ReleaseByteArrayElements(
            opusData,
            input,
            JNI_ABORT
    );


    /*
     * PCM 已经被 native 写入，
     * 所以必须提交修改。
     */

    env->ReleaseShortArrayElements(
            pcmOutput,
            pcmOutputData,
            0
    );


    /*
     * ========================================================
     * 检查 Decode 结果
     * ========================================================
     */

    if (
            decodedSamples < 0
            ) {

        LOGE(
                "opus_decode() 失败: %d",
                decodedSamples
        );

        return decodedSamples;
    }


    LOGD(
            "Opus 解码成功: Opus=%d bytes, PCM=%d samples",
            opusLength,
            decodedSamples
    );


    return decodedSamples;
}


/*
 * ============================================================
 * Opus Decoder Destroy
 * ============================================================
 */

extern "C"
JNIEXPORT void JNICALL
Java_com_example_walkie_OpusDecoder_nativeDestroy(
        JNIEnv* env,
        jobject thiz,
        jlong handle) {

    (void) env;
    (void) thiz;


    if (
            handle == 0
            ) {

        return;
    }


    OpusDecoder* decoder =
            reinterpret_cast<OpusDecoder*>(
                    handle
            );


    opus_decoder_destroy(
            decoder
    );


    LOGD(
            "Opus Decoder 已释放"
    );
}