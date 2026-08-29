package com.example.walkie

import android.util.Log
import kotlin.math.abs

class OpusDecoder {

    companion object {

        private const val TAG =
            "PRIVATE_RADIO_OPUS"

        private const val SAMPLE_RATE =
            16000

        private const val CHANNELS =
            1

        /*
         * 20ms @ 16kHz
         */
        private const val FRAME_SIZE =
            320

        /*
         * V15：
         *
         * W A K 1
         */
        private val AUDIO_MAGIC =
            byteArrayOf(
                0x57,
                0x41,
                0x4B,
                0x31
            )

        private const val AUDIO_HEADER_SIZE =
            8

        private const val MAX_OPUS_PACKET_SIZE =
            1208

        /*
         * 允许在 V15 中出现极少量乱序。
         *
         * 如果一个包比当前期望序号落后，
         * 直接丢弃。
         */
        private const val MAX_SEQUENCE_AHEAD =
            1000

        init {

            try {

                System.loadLibrary(
                    "privateradio"
                )

                Log.d(
                    TAG,
                    "Opus Native Library 加载成功"
                )

            } catch (
                e: Throwable
            ) {

                Log.e(
                    TAG,
                    "Opus Native Library 加载失败",
                    e
                )
            }
        }
    }

    /*
     * ============================================================
     * Native
     * ============================================================
     */

    @Volatile
    private var nativeHandle:
            Long =
        0L

    private val nativeLock =
        Any()

    /*
     * ============================================================
     * V15 网络统计
     * ============================================================
     */

    @Volatile
    private var hasSequence =
        false

    @Volatile
    private var expectedSequence =
        0

    @Volatile
    private var receivedPackets =
        0L

    @Volatile
    private var lostPackets =
        0L

    @Volatile
    private var duplicatePackets =
        0L

    @Volatile
    private var latePackets =
        0L

    @Volatile
    private var outOfOrderPackets =
        0L

    /*
     * ============================================================
     * 初始化
     * ============================================================
     */

    init {

        val handle =
            try {

                nativeCreate(
                    SAMPLE_RATE,
                    CHANNELS
                )

            } catch (
                e: Throwable
            ) {

                Log.e(
                    TAG,
                    "Opus Decoder 创建异常",
                    e
                )

                0L
            }

        if (
            handle == 0L
        ) {

            throw RuntimeException(
                "Opus Decoder 创建失败"
            )
        }

        nativeHandle =
            handle

        Log.d(
            TAG,
            "Opus Decoder 创建成功"
        )
    }

    /*
     * ============================================================
     * 解码
     * ============================================================
     */

    fun decode(
        opusData: ByteArray
    ): ShortArray {

        if (
            opusData.isEmpty()
        ) {

            return ShortArray(
                0
            )
        }

        /*
         * 兼容旧裸 Opus 数据。
         *
         * 如果不是 V15 格式，
         * 仍然按照普通 Opus 处理。
         */
        val framed =
            isFramedAudio(
                opusData
            )

        val payload:
                ByteArray

        if (
            framed
        ) {

            if (
                opusData.size <=
                AUDIO_HEADER_SIZE
            ) {

                return ShortArray(
                    0
                )
            }

            if (
                opusData.size >
                MAX_OPUS_PACKET_SIZE
            ) {

                Log.w(
                    TAG,
                    "V15 音频包过大: ${opusData.size}"
                )

                return ShortArray(
                    0
                )
            }

            val sequenceNumber =
                readSequence(
                    opusData
                )

            if (
                !acceptSequence(
                    sequenceNumber
                )
            ) {

                return ShortArray(
                    0
                )
            }

            payload =
                opusData.copyOfRange(
                    AUDIO_HEADER_SIZE,
                    opusData.size
                )

        } else {

            /*
             * 兼容 V14 裸 Opus。
             */
            if (
                opusData.size >
                1200
            ) {

                return ShortArray(
                    0
                )
            }

            payload =
                opusData
        }

        if (
            payload.isEmpty()
        ) {

            return ShortArray(
                0
            )
        }

        synchronized(
            nativeLock
        ) {

            val handle =
                nativeHandle

            if (
                handle == 0L
            ) {

                return ShortArray(
                    0
                )
            }

            val pcm =
                ShortArray(
                    FRAME_SIZE
                )

            val decodedSamples =
                try {

                    nativeDecode(
                        handle,
                        payload,
                        payload.size,
                        pcm
                    )

                } catch (
                    e: Throwable
                ) {

                    Log.e(
                        TAG,
                        "Opus Native Decode 异常 size=${payload.size}",
                        e
                    )

                    return ShortArray(
                        0
                    )
                }

            if (
                decodedSamples <= 0
            ) {

                return ShortArray(
                    0
                )
            }

            if (
                decodedSamples >
                FRAME_SIZE
            ) {

                Log.e(
                    TAG,
                    "Opus 返回非法 PCM: $decodedSamples"
                )

                return ShortArray(
                    0
                )
            }

            return if (
                decodedSamples ==
                FRAME_SIZE
            ) {

                pcm

            } else {

                try {

                    pcm.copyOf(
                        decodedSamples
                    )

                } catch (
                    e: Throwable
                ) {

                    Log.e(
                        TAG,
                        "PCM 截取失败",
                        e
                    )

                    ShortArray(
                        0
                    )
                }
            }
        }
    }

    /*
     * ============================================================
     * 判断 V15 音频包
     * ============================================================
     */

    private fun isFramedAudio(
        data: ByteArray
    ): Boolean {

        if (
            data.size <
            AUDIO_HEADER_SIZE
        ) {

            return false
        }

        return data[0] ==
                AUDIO_MAGIC[0] &&
                data[1] ==
                AUDIO_MAGIC[1] &&
                data[2] ==
                AUDIO_MAGIC[2] &&
                data[3] ==
                AUDIO_MAGIC[3]
    }

    /*
     * ============================================================
     * 读取序号
     * ============================================================
     */

    private fun readSequence(
        data: ByteArray
    ): Int {

        return (
                ((data[4].toInt() and 0xff) shl 24) or
                        ((data[5].toInt() and 0xff) shl 16) or
                        ((data[6].toInt() and 0xff) shl 8) or
                        (data[7].toInt() and 0xff)
                )
    }

    /*
     * ============================================================
     * 序号判断
     * ============================================================
     */

    private fun acceptSequence(
        sequenceNumber: Int
    ): Boolean {

        synchronized(
            this
        ) {

            /*
             * 第一包。
             */
            if (
                !hasSequence
            ) {

                hasSequence =
                    true

                expectedSequence =
                    sequenceNumber + 1

                receivedPackets++

                return true
            }

            val difference =
                sequenceNumber -
                        expectedSequence

            /*
             * 正好是下一包。
             */
            if (
                difference == 0
            ) {

                expectedSequence =
                    expectedSequence + 1

                receivedPackets++

                return true
            }

            /*
             * sequenceNumber 比 expectedSequence
             * 小，说明包迟到了/乱序到了。
             */
            if (
                difference < 0
            ) {

                /*
                 * 极少情况下同一个包重复到达。
                 */
                duplicatePackets++

                latePackets++

                return false
            }

            /*
             * difference > 0
             *
             * 中间有包没有收到。
             */
            if (
                difference <=
                MAX_SEQUENCE_AHEAD
            ) {

                lostPackets +=
                    difference.toLong()

                outOfOrderPackets++

                /*
                 * 直接跳到最新包之后。
                 *
                 * 这样不会因为一个丢包，
                 * 把后面的整段音频全部卡住。
                 */
                expectedSequence =
                    sequenceNumber + 1

                receivedPackets++

                return true
            }

            /*
             * 序号跳得异常大。
             *
             * 视为异常包，不让它污染统计。
             */
            return false
        }
    }

    /*
     * ============================================================
     * 网络统计
     * ============================================================
     */

    fun getReceivedPackets():
            Long {

        return receivedPackets
    }

    fun getLostPackets():
            Long {

        return lostPackets
    }

    fun getDuplicatePackets():
            Long {

        return duplicatePackets
    }

    fun getLatePackets():
            Long {

        return latePackets
    }

    fun getOutOfOrderPackets():
            Long {

        return outOfOrderPackets
    }

    fun getPacketLossRate():
            Float {

        val received =
            receivedPackets

        val lost =
            lostPackets

        val total =
            received + lost

        if (
            total <= 0L
        ) {

            return 0.0f
        }

        return (
                lost.toDouble() /
                        total.toDouble()
                )
            .toFloat()
    }

    fun resetNetworkStats() {

        synchronized(
            this
        ) {

            receivedPackets =
                0L

            lostPackets =
                0L

            duplicatePackets =
                0L

            latePackets =
                0L

            outOfOrderPackets =
                0L

            hasSequence =
                false

            expectedSequence =
                0
        }
    }

    /*
     * ============================================================
     * 释放
     * ============================================================
     */

    fun release() {

        synchronized(
            nativeLock
        ) {

            val handle =
                nativeHandle

            if (
                handle == 0L
            ) {

                return
            }

            nativeHandle =
                0L

            try {

                nativeDestroy(
                    handle
                )

                Log.d(
                    TAG,
                    "Opus Decoder 已释放"
                )

            } catch (
                e: Throwable
            ) {

                Log.e(
                    TAG,
                    "Opus Decoder 释放异常",
                    e
                )
            }
        }
    }

    /*
     * ============================================================
     * Native
     * ============================================================
     */

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int
    ): Long

    private external fun nativeDecode(
        handle: Long,
        opusData: ByteArray,
        opusLength: Int,
        pcmOutput: ShortArray
    ): Int

    private external fun nativeDestroy(
        handle: Long
    )
}