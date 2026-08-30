package com.example.walkie

import android.util.Log

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
         *
         * 16000 × 0.02 = 320 samples
         */
        const val FRAME_SIZE =
            320

        /*
         * 最大 Opus Payload。
         *
         * WalkieService 后续还会添加自己的
         * W23A 音频协议头。
         */
        private const val MAX_OPUS_PAYLOAD_SIZE =
            1200

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
     * Native Encoder 句柄。
     */
    @Volatile
    private var nativeHandle:
            Long =
        0L

    /*
     * 防止 encode() 与 release()
     * 同时操作 Native Encoder。
     */
    private val nativeLock =
        Any()

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

            nativeHandle =
                0L

            Log.e(
                TAG,
                "Opus Encoder 初始化失败"
            )
        }
    }

    /*
     * ============================================================
     * 编码
     * ============================================================
     *
     * 返回值：
     *
     *     纯 Opus Payload
     *
     * 注意：
     *
     * WalkieService 会在外层统一添加：
     *
     *     W23A
     *     StreamID
     *     Sequence
     *
     * 所以这里不能再次添加任何音频协议头。
     */

    fun encode(
        pcm: ShortArray
    ): ByteArray? {

        /*
         * 20ms / 16kHz / mono
         */
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

                    null

                } else {

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

                        null
                    }
                }
            }

        /*
         * 编码失败。
         */
        if (
            opusPayload == null ||
            opusPayload.isEmpty()
        ) {

            return null
        }

        /*
         * 防止异常数据包过大。
         */
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
         * 核心：
         *
         * 直接返回 Native Opus 数据。
         */
        return opusPayload
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
