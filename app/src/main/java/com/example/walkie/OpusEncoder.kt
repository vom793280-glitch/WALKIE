package com.example.walkie

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class OpusEncoder {

    companion object {

        private const val TAG =
            "PRIVATE_RADIO_OPUS"

        const val SAMPLE_RATE =
            16000

        const val CHANNELS =
            1

        /*
         * 20ms @ 16kHz / mono
         */
        const val FRAME_SIZE =
            320

        /*
         * V15 音频包头：
         *
         * W A K 1
         *
         * 4 bytes
         */
        private val AUDIO_MAGIC =
            byteArrayOf(
                0x57,
                0x41,
                0x4B,
                0x31
            )

        /*
         * 4 bytes sequence
         */
        private const val AUDIO_HEADER_SIZE =
            8

        /*
         * 与服务器 maxPacketSize 1500 保持安全距离。
         */
        private const val MAX_OPUS_PAYLOAD_SIZE =
            1200

        private const val MAX_FRAMED_PACKET_SIZE =
            AUDIO_HEADER_SIZE +
                    MAX_OPUS_PAYLOAD_SIZE

        private var nativeLibraryLoaded =
            false

        init {

            try {

                System.loadLibrary(
                    "privateradio"
                )

                nativeLibraryLoaded =
                    true

                Log.d(
                    TAG,
                    "Opus Native Library 加载成功"
                )

            } catch (
                e: Throwable
            ) {

                nativeLibraryLoaded =
                    false

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
     * V15 音频序号
     * ============================================================
     *
     * 每发送一个 Opus 包：
     *
     * 0
     * 1
     * 2
     * 3
     *
     * 溢出以后从 Int.MIN_VALUE 自然继续，
     * 接收端使用无符号差值判断。
     */
    private val sequence =
        AtomicInteger(0)

    /*
     * ============================================================
     * 初始化
     * ============================================================
     */

    init {

        if (
            nativeLibraryLoaded
        ) {

            val handle =
                try {

                    nativeCreate()

                } catch (
                    e: Throwable
                ) {

                    Log.e(
                        TAG,
                        "Opus Encoder 创建异常",
                        e
                    )

                    0L
                }

            if (
                handle == 0L
            ) {

                Log.e(
                    TAG,
                    "Opus Encoder 创建失败"
                )

                nativeHandle =
                    0L

            } else {

                nativeHandle =
                    handle

                Log.d(
                    TAG,
                    "Opus Encoder 创建成功 handle=$handle"
                )
            }

        } else {

            Log.e(
                TAG,
                "Opus Encoder 初始化失败：Native Library 未加载"
            )

            nativeHandle =
                0L
        }
    }

    /*
     * ============================================================
     * 编码
     * ============================================================
     *
     * 返回：
     *
     * [4字节 MAGIC][4字节 SEQUENCE][Opus]
     */
    fun encode(
        pcm: ShortArray
    ): ByteArray? {

        if (
            pcm.size !=
            FRAME_SIZE
        ) {

            Log.w(
                TAG,
                "PCM 帧大小错误: ${pcm.size}，要求=$FRAME_SIZE"
            )

            return null
        }

        val opusPayload =
            synchronized(
                nativeLock
            ) {

                val handle =
                    nativeHandle

                if (
                    handle == 0L
                ) {

                    Log.w(
                        TAG,
                        "Opus Encoder 未初始化或已释放"
                    )

                    return null
                }

                try {

                    nativeEncode(
                        handle,
                        pcm
                    )

                } catch (
                    e: Throwable
                ) {

                    Log.e(
                        TAG,
                        "Opus Native Encode 异常",
                        e
                    )

                    return null
                }
            }

        if (
            opusPayload == null ||
            opusPayload.isEmpty()
        ) {

            return null
        }

        if (
            opusPayload.size >
            MAX_OPUS_PAYLOAD_SIZE
        ) {

            Log.w(
                TAG,
                "Opus 编码包过大: ${opusPayload.size}"
            )

            return null
        }

        /*
         * V15 序号。
         */
        val seq =
            sequence.getAndIncrement()

        /*
         * [MAGIC 4][SEQ 4][OPUS]
         */
        val packet =
            ByteArray(
                MAX_FRAMED_PACKET_SIZE.coerceAtMost(
                    AUDIO_HEADER_SIZE +
                            opusPayload.size
                )
            )

        /*
         * 实际长度：
         */
        val actualLength =
            AUDIO_HEADER_SIZE +
                    opusPayload.size

        if (
            packet.size <
            actualLength
        ) {

            return null
        }

        /*
         * MAGIC
         */
        packet[0] =
            AUDIO_MAGIC[0]

        packet[1] =
            AUDIO_MAGIC[1]

        packet[2] =
            AUDIO_MAGIC[2]

        packet[3] =
            AUDIO_MAGIC[3]

        /*
         * Sequence，大端序。
         */
        packet[4] =
            ((seq ushr 24) and 0xff)
                .toByte()

        packet[5] =
            ((seq ushr 16) and 0xff)
                .toByte()

        packet[6] =
            ((seq ushr 8) and 0xff)
                .toByte()

        packet[7] =
            (seq and 0xff)
                .toByte()

        /*
         * Opus Payload
         */
        System.arraycopy(
            opusPayload,
            0,
            packet,
            AUDIO_HEADER_SIZE,
            opusPayload.size
        )

        /*
         * 因为上面创建的是固定容量，
         * 这里一定返回精确长度的副本。
         */
        return try {

            packet.copyOf(
                actualLength
            )

        } catch (
            e: Throwable
        ) {

            Log.e(
                TAG,
                "V15 音频包创建失败",
                e
            )

            null
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
                    "Opus Encoder 已释放"
                )

            } catch (
                e: Throwable
            ) {

                Log.e(
                    TAG,
                    "Opus Encoder 释放异常",
                    e
                )
            }
        }
    }

    /*
     * ============================================================
     * Native 方法
     * ============================================================
     */

    private external fun nativeCreate():
            Long

    private external fun nativeEncode(
        handle: Long,
        pcm: ShortArray
    ): ByteArray?

    private external fun nativeDestroy(
        handle: Long
    )
}