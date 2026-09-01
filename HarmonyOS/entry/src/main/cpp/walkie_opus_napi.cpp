#include "napi/native_api.h"

#include <opus.h>

#include <ohaudio/native_audiostreambuilder.h>
#include <ohaudio/native_audiocapturer.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

/*
 * ============================================================
 * WALKIE Native Module
 *
 * 最终只生成：
 *
 *     libwalkieopus.so
 *
 * 当前包含：
 *
 *   1. Opus Encoder
 *   2. Opus Decoder
 *   3. Native OHAudio Capturer
 *
 * HarmonyOS 7.0 / API 26
 *
 * 音频：
 *
 *   16000 Hz
 *   Mono
 *   S16LE
 *   RAW
 *
 * Opus：
 *
 *   24 kbps
 *   FEC = ON
 *   Packet Loss = 5%
 *   320 samples / 20 ms
 *
 * ============================================================
 */


/* ============================================================
 * Opus
 * ============================================================ */

static OpusEncoder* g_encoder =
    nullptr;

static OpusDecoder* g_decoder =
    nullptr;


/* ============================================================
 * OHAudio
 * ============================================================ */

static OH_AudioStreamBuilder* g_audio_builder =
    nullptr;

static OH_AudioCapturer* g_audio_capturer =
    nullptr;


/* ============================================================
 * PCM 统计
 * ============================================================ */

static uint64_t g_pcm_callbacks =
    0;

static uint64_t g_pcm_bytes =
    0;


/* ============================================================
 * 创建 Opus Encoder
 * ============================================================ */

static napi_value CreateEncoder(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    if (
        g_encoder != nullptr
    ) {

        napi_value result =
            nullptr;

        napi_get_boolean(
            env,
            true,
            &result
        );

        return result;
    }

    int error =
        OPUS_OK;

    g_encoder =
        opus_encoder_create(
            16000,
            1,
            OPUS_APPLICATION_VOIP,
            &error
        );

    if (
        g_encoder == nullptr ||
        error != OPUS_OK
    ) {

        g_encoder =
            nullptr;

        napi_value result =
            nullptr;

        napi_get_boolean(
            env,
            false,
            &result
        );

        return result;
    }

    /*
     * 24 kbps
     */

    opus_encoder_ctl(
        g_encoder,
        OPUS_SET_BITRATE(24000)
    );

    /*
     * In-band FEC
     */

    opus_encoder_ctl(
        g_encoder,
        OPUS_SET_INBAND_FEC(1)
    );

    /*
     * 预估 5% 丢包
     */

    opus_encoder_ctl(
        g_encoder,
        OPUS_SET_PACKET_LOSS_PERC(5)
    );

    napi_value result =
        nullptr;

    napi_get_boolean(
        env,
        true,
        &result
    );

    std::printf(
        "WALKIE OPUS: "
        "Encoder 创建成功\n"
    );

    return result;
}


/* ============================================================
 * Opus Encode
 *
 * 参数：
 *
 *   pcm: ArrayBuffer
 *
 * 固定：
 *
 *   640 bytes
 *   320 samples
 * ============================================================ */

static napi_value Encode(
    napi_env env,
    napi_callback_info info)
{
    size_t argc =
        1;

    napi_value argv[1] =
        {
            nullptr
        };

    napi_status status =
        napi_get_cb_info(
            env,
            info,
            &argc,
            argv,
            nullptr,
            nullptr
        );

    if (
        status != napi_ok ||
        argc < 1
    ) {

        return nullptr;
    }

    if (
        g_encoder == nullptr
    ) {

        return nullptr;
    }

    void* pcmData =
        nullptr;

    size_t pcmSize =
        0;

    status =
        napi_get_arraybuffer_info(
            env,
            argv[0],
            &pcmData,
            &pcmSize
        );

    if (
        status != napi_ok ||
        pcmData == nullptr
    ) {

        return nullptr;
    }

    if (
        pcmSize != 640
    ) {

        return nullptr;
    }

    const opus_int16* pcm =
        static_cast<const opus_int16*>(
            pcmData
        );

    unsigned char output[4000] =
        {};

    const int encodedBytes =
        opus_encode(
            g_encoder,
            pcm,
            320,
            output,
            4000
        );

    if (
        encodedBytes <= 0
    ) {

        return nullptr;
    }

    void* outputData =
        nullptr;

    napi_value result =
        nullptr;

    status =
        napi_create_arraybuffer(
            env,
            static_cast<size_t>(
                encodedBytes
            ),
            &outputData,
            &result
        );

    if (
        status != napi_ok ||
        outputData == nullptr
    ) {

        return nullptr;
    }

    std::memcpy(
        outputData,
        output,
        static_cast<size_t>(
            encodedBytes
        )
    );

    return result;
}


/* ============================================================
 * 销毁 Encoder
 * ============================================================ */

static napi_value DestroyEncoder(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    if (
        g_encoder != nullptr
    ) {

        opus_encoder_destroy(
            g_encoder
        );

        g_encoder =
            nullptr;
    }

    napi_value result =
        nullptr;

    napi_get_undefined(
        env,
        &result
    );

    return result;
}


/* ============================================================
 * 创建 Decoder
 * ============================================================ */

static napi_value CreateDecoder(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    if (
        g_decoder != nullptr
    ) {

        napi_value result =
            nullptr;

        napi_get_boolean(
            env,
            true,
            &result
        );

        return result;
    }

    int error =
        OPUS_OK;

    g_decoder =
        opus_decoder_create(
            16000,
            1,
            &error
        );

    if (
        g_decoder == nullptr ||
        error != OPUS_OK
    ) {

        g_decoder =
            nullptr;

        napi_value result =
            nullptr;

        napi_get_boolean(
            env,
            false,
            &result
        );

        return result;
    }

    napi_value result =
        nullptr;

    napi_get_boolean(
        env,
        true,
        &result
    );

    std::printf(
        "WALKIE OPUS: "
        "Decoder 创建成功\n"
    );

    return result;
}


/* ============================================================
 * Opus Decode
 * ============================================================ */

static napi_value Decode(
    napi_env env,
    napi_callback_info info)
{
    size_t argc =
        1;

    napi_value argv[1] =
        {
            nullptr
        };

    napi_status status =
        napi_get_cb_info(
            env,
            info,
            &argc,
            argv,
            nullptr,
            nullptr
        );

    if (
        status != napi_ok ||
        argc < 1
    ) {

        return nullptr;
    }

    if (
        g_decoder == nullptr
    ) {

        return nullptr;
    }

    void* opusData =
        nullptr;

    size_t opusSize =
        0;

    status =
        napi_get_arraybuffer_info(
            env,
            argv[0],
            &opusData,
            &opusSize
        );

    if (
        status != napi_ok ||
        opusData == nullptr ||
        opusSize == 0 ||
        opusSize > 1208
    ) {

        return nullptr;
    }

    opus_int16 pcm[1920] =
        {};

    const int decodedSamples =
        opus_decode(
            g_decoder,
            static_cast<const unsigned char*>(
                opusData
            ),
            static_cast<opus_int32>(
                opusSize
            ),
            pcm,
            1920,
            0
        );

    if (
        decodedSamples <= 0
    ) {

        return nullptr;
    }

    const size_t outputBytes =
        static_cast<size_t>(
            decodedSamples
        ) *
        sizeof(opus_int16);

    void* outputData =
        nullptr;

    napi_value result =
        nullptr;

    status =
        napi_create_arraybuffer(
            env,
            outputBytes,
            &outputData,
            &result
        );

    if (
        status != napi_ok ||
        outputData == nullptr
    ) {

        return nullptr;
    }

    std::memcpy(
        outputData,
        pcm,
        outputBytes
    );

    return result;
}


/* ============================================================
 * 销毁 Decoder
 * ============================================================ */

static napi_value DestroyDecoder(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    if (
        g_decoder != nullptr
    ) {

        opus_decoder_destroy(
            g_decoder
        );

        g_decoder =
            nullptr;
    }

    napi_value result =
        nullptr;

    napi_get_undefined(
        env,
        &result
    );

    return result;
}


/* ============================================================
 * Native OHAudio PCM 回调
 *
 * 注意：
 *
 * 回调线程只统计数据。
 * 不在这里调用 Opus / UDP / Audio API。
 *
 * ============================================================ */

static int32_t OnReadData(
    OH_AudioCapturer* capturer,
    void* userData,
    void* buffer,
    int32_t length)
{
    (void)capturer;
    (void)userData;
    (void)buffer;

    if (
        length <= 0
    ) {

        return 0;
    }

    g_pcm_callbacks +=
        1;

    g_pcm_bytes +=
        static_cast<uint64_t>(
            length
        );

    if (
        g_pcm_callbacks % 50 ==
        0
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "PCM callbacks=%llu bytes=%llu\n",
            static_cast<unsigned long long>(
                g_pcm_callbacks
            ),
            static_cast<unsigned long long>(
                g_pcm_bytes
            )
        );
    }

    return 0;
}


/* ============================================================
 * 创建 OHAudio Capturer
 * ============================================================ */

static bool CreateAudioCapturer()
{
    if (
        g_audio_capturer != nullptr
    ) {

        return true;
    }

    /*
     * --------------------------------------------------------
     * Builder
     * --------------------------------------------------------
     */

    OH_AudioStream_Result result =
        OH_AudioStreamBuilder_Create(
            &g_audio_builder,
            AUDIOSTREAM_TYPE_CAPTURER
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "Builder 创建失败 ret=%d\n",
            static_cast<int>(result)
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * 16000 Hz
     * --------------------------------------------------------
     */

    result =
        OH_AudioStreamBuilder_SetSamplingRate(
            g_audio_builder,
            16000
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "SetSamplingRate 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * Mono
     * --------------------------------------------------------
     */

    result =
        OH_AudioStreamBuilder_SetChannelCount(
            g_audio_builder,
            1
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "SetChannelCount 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * S16LE
     * --------------------------------------------------------
     */

    result =
        OH_AudioStreamBuilder_SetSampleFormat(
            g_audio_builder,
            AUDIOSTREAM_SAMPLE_S16LE
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "SetSampleFormat 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * RAW
     * --------------------------------------------------------
     */

    result =
        OH_AudioStreamBuilder_SetEncodingType(
            g_audio_builder,
            AUDIOSTREAM_ENCODING_TYPE_RAW
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "SetEncodingType 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * MIC
     * --------------------------------------------------------
     */

    result =
        OH_AudioStreamBuilder_SetCapturerInfo(
            g_audio_builder,
            AUDIOSTREAM_SOURCE_TYPE_MIC
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "SetCapturerInfo 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * OHAudio 数据回调
     *
     * 使用官方 OnReadData Callback。
     * --------------------------------------------------------
     */

    OH_AudioCapturer_Callbacks callbacks =
        {};

    callbacks.OH_AudioCapturer_OnReadData =
        OnReadData;

    result =
        OH_AudioStreamBuilder_SetCapturerCallback(
            g_audio_builder,
            callbacks,
            nullptr
        );

    if (
        result != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "SetCapturerCallback 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        return false;
    }

    /*
     * --------------------------------------------------------
     * 创建 Capturer
     * --------------------------------------------------------
     */

    OH_AudioCapturer* capturer =
        nullptr;

    result =
        OH_AudioStreamBuilder_GenerateCapturer(
            g_audio_builder,
            &capturer
        );

    if (
        result != AUDIOSTREAM_SUCCESS ||
        capturer == nullptr
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "GenerateCapturer 失败 ret=%d\n",
            static_cast<int>(result)
        );

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;

        g_audio_capturer =
            nullptr;

        return false;
    }

    /*
     * 保存 Capturer
     */

    g_audio_capturer =
        capturer;

    g_pcm_callbacks =
        0;

    g_pcm_bytes =
        0;

    std::printf(
        "WALKIE OHAUDIO: "
        "★Native Capturer 创建成功★\n"
    );

    return true;
}


/* ============================================================
 * startCapture()
 * ============================================================ */

static napi_value StartCapture(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    const bool created =
        CreateAudioCapturer();

    if (
        !created
    ) {

        napi_value result =
            nullptr;

        napi_get_boolean(
            env,
            false,
            &result
        );

        return result;
    }

    const OH_AudioStream_Result ret =
        OH_AudioCapturer_Start(
            g_audio_capturer
        );

    if (
        ret != AUDIOSTREAM_SUCCESS
    ) {

        std::printf(
            "WALKIE OHAUDIO: "
            "Start 失败 ret=%d\n",
            static_cast<int>(ret)
        );

        napi_value result =
            nullptr;

        napi_get_boolean(
            env,
            false,
            &result
        );

        return result;
    }

    std::printf(
        "WALKIE OHAUDIO: "
        "★Native Capturer 启动成功★\n"
    );

    napi_value result =
        nullptr;

    napi_get_boolean(
        env,
        true,
        &result
    );

    return result;
}


/* ============================================================
 * stopCapture()
 * ============================================================ */

static napi_value StopCapture(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    if (
        g_audio_capturer != nullptr
    ) {

        const OH_AudioStream_Result ret =
            OH_AudioCapturer_Stop(
                g_audio_capturer
            );

        std::printf(
            "WALKIE OHAUDIO: "
            "Stop ret=%d\n",
            static_cast<int>(ret)
        );
    }

    napi_value result =
        nullptr;

    napi_get_undefined(
        env,
        &result
    );

    return result;
}


/* ============================================================
 * releaseCapture()
 * ============================================================ */

static napi_value ReleaseCapture(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    /*
     * Capturer
     */

    if (
        g_audio_capturer != nullptr
    ) {

        const OH_AudioStream_Result ret =
            OH_AudioCapturer_Release(
                g_audio_capturer
            );

        std::printf(
            "WALKIE OHAUDIO: "
            "Release ret=%d\n",
            static_cast<int>(ret)
        );

        g_audio_capturer =
            nullptr;
    }

    /*
     * Builder
     */

    if (
        g_audio_builder != nullptr
    ) {

        OH_AudioStreamBuilder_Destroy(
            g_audio_builder
        );

        g_audio_builder =
            nullptr;
    }

    g_pcm_callbacks =
        0;

    g_pcm_bytes =
        0;

    napi_value result =
        nullptr;

    napi_get_undefined(
        env,
        &result
    );

    return result;
}


/* ============================================================
 * 获取 PCM Callback 数量
 * ============================================================ */

static napi_value GetPcmStats(
    napi_env env,
    napi_callback_info info)
{
    (void)info;

    napi_value result =
        nullptr;

    napi_create_bigint_uint64(
        env,
        g_pcm_callbacks,
        &result
    );

    return result;
}


/* ============================================================
 * Native 模块初始化
 * ============================================================ */

static napi_value Init(
    napi_env env,
    napi_value exports)
{
    napi_property_descriptor desc[] = {

        /*
         * Opus
         */

        {
            "createEncoder",
            nullptr,
            CreateEncoder,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "encode",
            nullptr,
            Encode,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "destroyEncoder",
            nullptr,
            DestroyEncoder,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "createDecoder",
            nullptr,
            CreateDecoder,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "decode",
            nullptr,
            Decode,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "destroyDecoder",
            nullptr,
            DestroyDecoder,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        /*
         * Native OHAudio
         */

        {
            "startCapture",
            nullptr,
            StartCapture,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "stopCapture",
            nullptr,
            StopCapture,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "releaseCapture",
            nullptr,
            ReleaseCapture,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        },

        {
            "getPcmStats",
            nullptr,
            GetPcmStats,
            nullptr,
            nullptr,
            nullptr,
            napi_default,
            nullptr
        }
    };

    napi_define_properties(
        env,
        exports,
        sizeof(desc) /
            sizeof(desc[0]),
        desc
    );

    return exports;
}


/* ============================================================
 * Native Module
 * ============================================================ */

static napi_module walkieOpusModule = {

    .nm_version = 1,

    .nm_flags = 0,

    .nm_filename = nullptr,

    .nm_register_func = Init,

    .nm_modname = "walkieopus",

    .nm_priv = nullptr,

    .reserved = { 0 }
};


/* ============================================================
 * 注册
 * ============================================================ */

extern "C"
__attribute__((constructor))
void RegisterWalkieOpusModule()
{
    napi_module_register(
        &walkieOpusModule
    );
}