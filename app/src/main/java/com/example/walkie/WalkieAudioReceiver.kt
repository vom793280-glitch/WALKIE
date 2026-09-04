package com.example.walkie

import com.example.walkie.audio.WalkieAudioPlayback

class WalkieAudioReceiver(
    private val sampleRate: Int,
    private val audioMaxPacketSize: Int,
    private val maxOpusPacketSize: Int,
    private val maxDecodedPcmSamples: Int,
    private val audioProtocol: WalkieAudioProtocol,
    private val opusDecoderProvider: () -> OpusDecoder?,
    private val audioPlayback: WalkieAudioPlayback,
    private val recordAudioReceive: (Int) -> Unit,
    private val logger: (String) -> Unit
) {

    private var consecutiveDecodeFailures =
        0

    fun reset() {

        consecutiveDecodeFailures =
            0
    }

    fun process(
        buffer: ByteArray,
        length: Int
    ) {

        if (
            length <= 0 ||
            length > audioMaxPacketSize
        ) {

            logger(
                "WALKIE AUDIO: " +
                        "drop invalid packet size=$length"
            )

            return
        }

        val decoder =
            opusDecoderProvider()
                ?: return

        val audioData =
            ByteArray(
                length
            )

        recordAudioReceive(
            length
        )

        System.arraycopy(
            buffer,
            0,
            audioData,
            0,
            length
        )

        val parsedV231 =
            audioProtocol.parsePacket(
                audioData
            )

        val opusPayload =
            if (
                parsedV231 != null
            ) {

                audioProtocol.reorder(
                    parsedV231.first,
                    parsedV231.second,
                    parsedV231.third
                )

            } else {

                audioData
            }

        if (
            opusPayload == null
        ) {

            return
        }

        if (
            opusPayload.isEmpty()
        ) {

            logger(
                "WALKIE AUDIO: " +
                        "drop empty Opus payload"
            )

            return
        }

        if (
            opusPayload.size >
            maxOpusPacketSize
        ) {

            logger(
                "WALKIE AUDIO: " +
                        "drop oversized Opus payload=" +
                        opusPayload.size
            )

            return
        }

        val pcmData =
            try {

                decoder.decode(
                    opusPayload
                )

            } catch (
                e: Throwable
            ) {

                logger(
                    "WALKIE AUDIO: " +
                            "decoder exception=${e.message}"
                )

                null
            }

        if (
            pcmData == null ||
            pcmData.isEmpty()
        ) {

            consecutiveDecodeFailures =
                (
                        consecutiveDecodeFailures + 1
                        )
                    .coerceAtMost(
                        20
                    )

            if (
                consecutiveDecodeFailures >=
                3
            ) {

                audioPlayback
                    .requestRecovery()

                logger(
                    "WALKIE AUDIO: " +
                            "连续Opus解码失败=" +
                            consecutiveDecodeFailures +
                            "，请求播放恢复"
                )
            }

            return
        }

        consecutiveDecodeFailures =
            0

        if (
            pcmData.size >
            maxDecodedPcmSamples
        ) {

            logger(
                "WALKIE AUDIO: " +
                        "drop invalid PCM=${pcmData.size}"
            )

            return
        }

        if (
            pcmData.size < 80 ||
            pcmData.size >
            sampleRate / 5
        ) {

            logger(
                "WALKIE AUDIO: " +
                        "drop abnormal PCM=${pcmData.size}"
            )

            return
        }

        val pcmBytes =
            ByteArray(
                pcmData.size * 2
            )

        var i =
            0

        while (
            i <
            pcmData.size
        ) {

            val sample =
                pcmData[i].toInt()

            pcmBytes[i * 2] =
                (
                        sample and
                                0xff
                        ).toByte()

            pcmBytes[i * 2 + 1] =
                (
                        (sample shr 8) and
                                0xff
                        ).toByte()

            i++
        }

        try {

            audioPlayback.enqueue(
                pcmBytes
            )

        } catch (
            throwable: Throwable
        ) {

            logger(
                "WALKIE AUDIO: " +
                        "独立播放模块加入PCM失败: " +
                        throwable.message
            )
        }
    }
}