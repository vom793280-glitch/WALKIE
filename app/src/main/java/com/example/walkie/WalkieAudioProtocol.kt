package com.example.walkie

import java.util.TreeMap

/**
 * WALKIE V23.1 / V24.9.1 音频协议。
 *
 * 负责：
 *
 * 1. W23A封包
 * 2. W23A解析
 * 3. Sequence判断
 * 4. 乱序缓存
 * 5. 丢包等待
 * 6. 大跨度Sequence重新同步
 *
 * 本文件不负责：
 *
 * 1. Opus编码
 * 2. Opus解码
 * 3. AudioTrack
 * 4. UDP收发
 */
class WalkieAudioProtocol(
    private val streamId: Long,
    private val logger: (String) -> Unit
) {

    companion object {

        const val MAGIC = "W23A"

        const val HEADER_SIZE = 12

        const val JITTER_CAPACITY = 8

        const val MAX_WAIT_MS = 60L

        const val MAX_OPUS_PACKET_SIZE = 1208

        const val MAX_PACKET_SIZE =
            MAX_OPUS_PACKET_SIZE +
                    HEADER_SIZE

        private const val UINT32_MASK =
            0xFFFF_FFFFL

        private const val HALF_UINT32 =
            0x8000_0000L
    }

    private var txSequence = 0L

    private var rxStreamId = -1L

    private var expectedSequence = -1L

    private var lostPackets = 0L

    private var reorderedPackets = 0L

    private var duplicatePackets = 0L

    private var gapStartTime = 0L

    private val lock = Any()

    private val jitterBuffer =
        TreeMap<Long, ByteArray>()

    fun buildPacket(
        opusData: ByteArray
    ): ByteArray {

        val sequence =
            txSequence and UINT32_MASK

        val result =
            ByteArray(
                HEADER_SIZE +
                        opusData.size
            )

        result[0] =
            'W'.code.toByte()

        result[1] =
            '2'.code.toByte()

        result[2] =
            '3'.code.toByte()

        result[3] =
            'A'.code.toByte()

        val safeStreamId =
            streamId and UINT32_MASK

        result[4] =
            ((safeStreamId shr 24) and 0xFF)
                .toByte()

        result[5] =
            ((safeStreamId shr 16) and 0xFF)
                .toByte()

        result[6] =
            ((safeStreamId shr 8) and 0xFF)
                .toByte()

        result[7] =
            (safeStreamId and 0xFF)
                .toByte()

        result[8] =
            ((sequence shr 24) and 0xFF)
                .toByte()

        result[9] =
            ((sequence shr 16) and 0xFF)
                .toByte()

        result[10] =
            ((sequence shr 8) and 0xFF)
                .toByte()

        result[11] =
            (sequence and 0xFF)
                .toByte()

        System.arraycopy(
            opusData,
            0,
            result,
            HEADER_SIZE,
            opusData.size
        )

        txSequence =
            (
                    txSequence +
                            1L
                    ) and
                    UINT32_MASK

        return result
    }

    fun parsePacket(
        packet: ByteArray
    ): Triple<Long, Long, ByteArray>? {

        if (
            packet.size <=
            HEADER_SIZE
        ) {
            return null
        }

        if (
            packet[0] != 'W'.code.toByte() ||
            packet[1] != '2'.code.toByte() ||
            packet[2] != '3'.code.toByte() ||
            packet[3] != 'A'.code.toByte()
        ) {
            return null
        }

        val parsedStreamId =
            (
                    ((packet[4].toLong() and 0xFF) shl 24) or
                            ((packet[5].toLong() and 0xFF) shl 16) or
                            ((packet[6].toLong() and 0xFF) shl 8) or
                            (packet[7].toLong() and 0xFF)
                    ) and
                    UINT32_MASK

        val sequence =
            (
                    ((packet[8].toLong() and 0xFF) shl 24) or
                            ((packet[9].toLong() and 0xFF) shl 16) or
                            ((packet[10].toLong() and 0xFF) shl 8) or
                            (packet[11].toLong() and 0xFF)
                    ) and
                    UINT32_MASK

        val opusLength =
            packet.size -
                    HEADER_SIZE

        if (
            opusLength <= 0 ||
            opusLength >
            MAX_OPUS_PACKET_SIZE
        ) {
            return null
        }

        val opus =
            ByteArray(
                opusLength
            )

        System.arraycopy(
            packet,
            HEADER_SIZE,
            opus,
            0,
            opusLength
        )

        return Triple(
            parsedStreamId,
            sequence,
            opus
        )
    }

    fun resetReceiveState() {

        synchronized(lock) {

            jitterBuffer.clear()

            rxStreamId =
                -1L

            expectedSequence =
                -1L

            gapStartTime =
                0L
        }
    }

    fun reorder(
        streamId: Long,
        sequence: Long,
        opusData: ByteArray
    ): ByteArray? {

        synchronized(lock) {

            /*
             * 新音频流。
             */
            if (
                rxStreamId !=
                streamId
            ) {

                jitterBuffer.clear()

                rxStreamId =
                    streamId

                expectedSequence =
                    (
                            sequence +
                                    1L
                            ) and
                            UINT32_MASK

                gapStartTime =
                    0L

                logger(
                    "WALKIE AUDIO: " +
                            "V23.1 新音频流 " +
                            "stream=$streamId " +
                            "seq=$sequence"
                )

                return opusData
            }

            val expected =
                expectedSequence and
                        UINT32_MASK

            /*
             * 正常连续包。
             */
            if (
                sequence ==
                expected
            ) {

                expectedSequence =
                    (
                            expected +
                                    1L
                            ) and
                            UINT32_MASK

                gapStartTime =
                    0L

                return opusData
            }

            val forwardDiff =
                (
                        sequence -
                                expected
                        ) and
                        UINT32_MASK

            /*
             * 大跨度直接重新同步。
             */
            val largeGapThreshold =
                JITTER_CAPACITY * 4

            if (
                forwardDiff >
                largeGapThreshold.toLong() &&
                forwardDiff <
                HALF_UINT32
            ) {

                val skippedPackets =
                    forwardDiff - 1L

                lostPackets +=
                    skippedPackets

                jitterBuffer.clear()

                expectedSequence =
                    (
                            sequence +
                                    1L
                            ) and
                            UINT32_MASK

                gapStartTime =
                    0L

                logger(
                    "WALKIE AUDIO: " +
                            "V23.1 检测到大跨度序号，" +
                            "立即重新同步 " +
                            "expected=$expected " +
                            "current=$sequence " +
                            "skip=$skippedPackets " +
                            "lost=$lostPackets"
                )

                return opusData
            }

            /*
             * 落后 / 重复包。
             */
            if (
                forwardDiff >=
                HALF_UINT32
            ) {

                duplicatePackets++

                return null
            }

            /*
             * 少量领先。
             */
            if (
                forwardDiff <=
                JITTER_CAPACITY.toLong()
            ) {

                if (
                    jitterBuffer.containsKey(
                        sequence
                    )
                ) {

                    duplicatePackets++

                    return null
                }

                if (
                    jitterBuffer.size >=
                    JITTER_CAPACITY
                ) {

                    jitterBuffer.pollFirstEntry()

                    lostPackets++
                }

                jitterBuffer[
                    sequence
                ] =
                    opusData

                reorderedPackets++

                if (
                    gapStartTime ==
                    0L
                ) {

                    gapStartTime =
                        System.currentTimeMillis()
                }

                val expectedPacket =
                    jitterBuffer[
                        expectedSequence
                    ]

                if (
                    expectedPacket !=
                    null
                ) {

                    jitterBuffer.remove(
                        expectedSequence
                    )

                    expectedSequence =
                        (
                                expectedSequence +
                                        1L
                                ) and
                                UINT32_MASK

                    gapStartTime =
                        0L

                    return expectedPacket
                }

                val now =
                    System.currentTimeMillis()

                if (
                    now -
                    gapStartTime >=
                    MAX_WAIT_MS
                ) {

                    val lostSequence =
                        expectedSequence

                    lostPackets++

                    expectedSequence =
                        (
                                lostSequence +
                                        1L
                                ) and
                                UINT32_MASK

                    gapStartTime =
                        now

                    logger(
                        "WALKIE AUDIO: " +
                                "V23.1 丢包 seq=$lostSequence " +
                                "lost=$lostPackets"
                    )

                    val nextPacket =
                        jitterBuffer.remove(
                            expectedSequence
                        )

                    if (
                        nextPacket !=
                        null
                    ) {

                        expectedSequence =
                            (
                                    expectedSequence +
                                            1L
                                    ) and
                                    UINT32_MASK

                        gapStartTime =
                            0L

                        return nextPacket
                    }
                }

                return null
            }

            /*
             * 特殊情况：
             * 直接重新同步。
             */
            jitterBuffer.clear()

            expectedSequence =
                (
                        sequence +
                                1L
                        ) and
                        UINT32_MASK

            gapStartTime =
                0L

            logger(
                "WALKIE AUDIO: " +
                        "V23.1 异常序号状态，" +
                        "强制重新同步 seq=$sequence"
            )

            return opusData
        }
    }

    fun getLostPackets(): Long =
        synchronized(lock) {
            lostPackets
        }

    fun getReorderedPackets(): Long =
        synchronized(lock) {
            reorderedPackets
        }

    fun getDuplicatePackets(): Long =
        synchronized(lock) {
            duplicatePackets
        }

    fun getTxSequence(): Long =
        txSequence and UINT32_MASK

    fun isSequenceAhead(
        sequence: Long,
        expected: Long
    ): Boolean {

        val diff =
            (
                    sequence -
                            expected
                    ) and
                    UINT32_MASK

        return diff != 0L &&
                diff < HALF_UINT32
    }
}