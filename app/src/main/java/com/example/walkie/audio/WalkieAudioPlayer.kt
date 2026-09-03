package com.example.walkie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * WALKIE 音频播放器
 *
 * V24.9.1
 *
 * 负责：
 *
 * 1. AudioTrack 创建
 * 2. AudioTrack 生命周期
 * 3. PCM 写入
 * 4. 播放启动
 * 5. 播放失败安全恢复
 * 6. 扬声器初始路由
 *
 * 不负责：
 *
 * 1. UDP
 * 2. Opus
 * 3. 抖动缓冲
 * 4. 播放队列
 * 5. 网络
 * 6. 频道
 * 7. PTT
 */
class WalkieAudioPlayer(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val packetSize: Int = 640,
    private val gain: Float = 1.0f,
    private val logger: (String) -> Unit = {}
) {

    companion object {

        private const val TAG =
            "WALKIE AUDIO PLAYER"

        private const val CHANNEL_MASK =
            AudioFormat.CHANNEL_OUT_MONO

        private const val ENCODING =
            AudioFormat.ENCODING_PCM_16BIT

        private const val MAX_PCM_DATA_SIZE =
            8192
    }

    /*
     * ============================================================
     * AudioTrack 生命周期锁
     * ============================================================
     *
     * 所有：
     *
     * 1. 创建
     * 2. write
     * 3. play
     * 4. release
     *
     * 都经过同一把锁。
     *
     * 防止：
     *
     * playback线程
     *      ↓
     * write()
     *
     * 同时
     *
     * Service线程
     *      ↓
     * release()
     *
     * 造成生命周期竞争。
     */
    private val lock =
        Any()

    @Volatile
    private var audioTrack:
            AudioTrack? =
        null

    /*
     * ============================================================
     * 获取当前 Track
     * ============================================================
     */
    fun currentTrack(): AudioTrack? {

        return synchronized(lock) {

            audioTrack
        }
    }

    /*
     * ============================================================
     * 判断播放器是否有效
     * ============================================================
     */
    fun isReady(): Boolean {

        val track =
            synchronized(lock) {

                audioTrack
            }

        if (
            track == null
        ) {

            return false
        }

        return try {

            track.state ==
                    AudioTrack.STATE_INITIALIZED

        } catch (_: Throwable) {

            false
        }
    }

    /*
     * ============================================================
     * 确保 AudioTrack 存在
     * ============================================================
     *
     * 注意：
     *
     * 创建以后不立即 play()。
     *
     * 收到真正 PCM 后：
     *
     * write()
     *   ↓
     * 成功
     *   ↓
     * play()
     */
    fun ensureAudioPlayer(): Boolean {

        synchronized(lock) {

            val current =
                audioTrack

            if (
                current != null &&
                isTrackUsableLocked(
                    current
                )
            ) {

                return true
            }

            releaseLocked()

            return try {

                createTrackLocked()

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: 创建AudioTrack异常=${e.message}"
                )

                false
            }
        }
    }

    /*
     * ============================================================
     * 创建 AudioTrack
     * ============================================================
     */
    private fun createTrackLocked(): Boolean {

        val minBufferSize =
            try {

                AudioTrack.getMinBufferSize(
                    sampleRate,
                    CHANNEL_MASK,
                    ENCODING
                )

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: getMinBufferSize异常=${e.message}"
                )

                return false
            }

        if (
            minBufferSize <= 0
        ) {

            logger(
                "$TAG: getMinBufferSize失败=$minBufferSize"
            )

            return false
        }

        val bufferSize =
            maxOf(
                minBufferSize * 4,
                packetSize * 16
            )

        val attributes =
            try {

                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .setFlags(
                        AudioAttributes.FLAG_LOW_LATENCY
                    )
                    .build()

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: AudioAttributes创建异常=${e.message}"
                )

                return false
            }

        val format =
            try {

                AudioFormat.Builder()
                    .setSampleRate(
                        sampleRate
                    )
                    .setEncoding(
                        ENCODING
                    )
                    .setChannelMask(
                        CHANNEL_MASK
                    )
                    .build()

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: AudioFormat创建异常=${e.message}"
                )

                return false
            }

        val track =
            try {

                AudioTrack.Builder()
                    .setAudioAttributes(
                        attributes
                    )
                    .setAudioFormat(
                        format
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .build()

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: AudioTrack.Builder异常=${e.message}"
                )

                return false
            }

        /*
         * ============================================================
         * 检查初始化状态
         * ============================================================
         */
        val initialized =
            try {

                track.state ==
                        AudioTrack.STATE_INITIALIZED

            } catch (_: Throwable) {

                false
            }

        if (
            !initialized
        ) {

            logger(
                "$TAG: AudioTrack初始化失败"
            )

            try {

                track.release()

            } catch (_: Throwable) {
            }

            return false
        }

        /*
         * ============================================================
         * 只在创建新 Track 时设置扬声器。
         *
         * 不再在每一次 write() 前重复调用
         * setPreferredDevice()。
         *
         * 这是 V24.9.1 第一阶段针对：
         *
         * “收到第一包声音瞬间崩溃”
         *
         * 的重要安全调整。
         * ============================================================
         */
        try {

            setSpeakerLocked(
                track
            )

        } catch (
            e: Throwable
        ) {

            logger(
                "$TAG: 初始扬声器设置失败=${e.message}"
            )
        }

        /*
         * ============================================================
         * 音量
         * ============================================================
         */
        try {

            track.setVolume(
                gain.coerceIn(
                    0.0f,
                    1.0f
                )
            )

        } catch (
            e: Throwable
        ) {

            logger(
                "$TAG: 设置音量失败=${e.message}"
            )
        }

        /*
         * ============================================================
         * 只有到这里才正式交给播放器管理。
         * ============================================================
         */
        audioTrack =
            track

        logger(
            "$TAG: AudioTrack创建成功 " +
                    "buffer=$bufferSize"
        )

        return true
    }

    /*
     * ============================================================
     * PCM播放
     * ============================================================
     */
    fun writeAndPlay(
        pcmData: ByteArray
    ): Boolean {

        /*
         * 基础数据检查
         */
        if (
            pcmData.isEmpty()
        ) {

            return false
        }

        if (
            pcmData.size % 2 != 0
        ) {

            return false
        }

        if (
            pcmData.size > MAX_PCM_DATA_SIZE
        ) {

            logger(
                "$TAG: PCM数据过大=${pcmData.size}"
            )

            return false
        }

        synchronized(lock) {

            var track =
                audioTrack

            /*
             * ========================================================
             * 当前 Track 不存在或者已经失效
             * ========================================================
             */
            if (
                track == null ||
                !isTrackUsableLocked(
                    track
                )
            ) {

                if (
                    !ensureAudioPlayer()
                ) {

                    return false
                }

                track =
                    audioTrack

                if (
                    track == null ||
                    !isTrackUsableLocked(
                        track
                    )
                ) {

                    return false
                }
            }

            /*
             * 到这里 Track 已经确定。
             *
             * 注意：
             *
             * 这里不再调用 setPreferredDevice()。
             */
            val currentTrack =
                track

            if (
                currentTrack == null
            ) {

                return false
            }

            return try {

                /*
                 * ====================================================
                 * 增益处理
                 * ====================================================
                 */
                val data =
                    applyGain(
                        pcmData
                    )

                if (
                    data.isEmpty() ||
                    data.size % 2 != 0
                ) {

                    return false
                }

                /*
                 * ====================================================
                 * 写入 PCM
                 * ====================================================
                 */
                val result =
                    currentTrack.write(
                        data,
                        0,
                        data.size,
                        AudioTrack.WRITE_BLOCKING
                    )

                /*
                 * ====================================================
                 * Android AudioTrack：
                 *
                 * write < 0
                 * 说明本次没有正常写入。
                 *
                 * ERROR_DEAD_OBJECT：
                 * 当前Track已经失效，
                 * 必须释放并重新创建。
                 * ====================================================
                 */
                if (
                    result < 0
                ) {

                    logger(
                        "$TAG: AudioTrack.write错误=$result"
                    )

                    releaseIfCurrentLocked(
                        currentTrack
                    )

                    return false
                }

                /*
                 * 理论上 WRITE_BLOCKING 应该写完整。
                 *
                 * 如果出现短写，
                 * 不继续往下 play，
                 * 交给上层恢复。
                 */
                if (
                    result != data.size
                ) {

                    logger(
                        "$TAG: AudioTrack短写 " +
                                "result=$result " +
                                "expected=${data.size}"
                    )

                    releaseIfCurrentLocked(
                        currentTrack
                    )

                    return false
                }

                /*
                 * ====================================================
                 * 写入成功之后才进入 PLAYING。
                 * ====================================================
                 */
                val playState =
                    try {

                        currentTrack.playState

                    } catch (
                        e: Throwable
                    ) {

                        logger(
                            "$TAG: 获取playState异常=${e.message}"
                        )

                        AudioTrack.PLAYSTATE_STOPPED
                    }

                if (
                    playState !=
                    AudioTrack.PLAYSTATE_PLAYING
                ) {

                    try {

                        currentTrack.play()

                    } catch (
                        e: Throwable
                    ) {

                        logger(
                            "$TAG: AudioTrack.play异常=${e.message}"
                        )

                        releaseIfCurrentLocked(
                            currentTrack
                        )

                        return false
                    }
                }

                true

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: write/play异常=${e.message}"
                )

                releaseIfCurrentLocked(
                    currentTrack
                )

                false
            }
        }
    }

    /*
     * ============================================================
     * 只写 PCM
     * ============================================================
     */
    fun write(
        pcmData: ByteArray
    ): Boolean {

        if (
            pcmData.isEmpty()
        ) {

            return false
        }

        if (
            pcmData.size % 2 != 0
        ) {

            return false
        }

        if (
            pcmData.size > MAX_PCM_DATA_SIZE
        ) {

            return false
        }

        synchronized(lock) {

            val track =
                audioTrack
                    ?: return false

            if (
                !isTrackUsableLocked(
                    track
                )
            ) {

                releaseIfCurrentLocked(
                    track
                )

                return false
            }

            return try {

                val data =
                    applyGain(
                        pcmData
                    )

                val result =
                    track.write(
                        data,
                        0,
                        data.size,
                        AudioTrack.WRITE_BLOCKING
                    )

                if (
                    result !=
                    data.size
                ) {

                    if (
                        result < 0
                    ) {

                        logger(
                            "$TAG: write错误=$result"
                        )

                    } else {

                        logger(
                            "$TAG: write短写=$result/" +
                                    data.size
                        )
                    }

                    releaseIfCurrentLocked(
                        track
                    )

                    false

                } else {

                    true
                }

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: write异常=${e.message}"
                )

                releaseIfCurrentLocked(
                    track
                )

                false
            }
        }
    }

    /*
     * ============================================================
     * underrun
     * ============================================================
     */
    fun getUnderrunCount(): Int {

        val track =
            synchronized(lock) {

                audioTrack
            }

        if (
            track == null
        ) {

            return 0
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.N
        ) {

            return 0
        }

        return try {

            track.underrunCount

        } catch (
            _: Throwable
        ) {

            0
        }
    }

    /*
     * ============================================================
     * 播放状态
     * ============================================================
     */
    fun getPlayState(): Int {

        val track =
            synchronized(lock) {

                audioTrack
            }

        if (
            track == null
        ) {

            return AudioTrack.PLAYSTATE_STOPPED
        }

        return try {

            track.playState

        } catch (
            _: Throwable
        ) {

            AudioTrack.PLAYSTATE_STOPPED
        }
    }

    /*
     * ============================================================
     * 暂停
     * ============================================================
     */
    fun pause() {

        synchronized(lock) {

            val track =
                audioTrack
                    ?: return

            try {

                if (
                    track.playState ==
                    AudioTrack.PLAYSTATE_PLAYING
                ) {

                    track.pause()
                }

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: pause异常=${e.message}"
                )
            }
        }
    }

    /*
     * ============================================================
     * Flush
     * ============================================================
     */
    fun flush() {

        synchronized(lock) {

            val track =
                audioTrack
                    ?: return

            try {

                track.flush()

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: flush异常=${e.message}"
                )
            }
        }
    }

    /*
     * ============================================================
     * 完整释放
     * ============================================================
     */
    fun release() {

        synchronized(lock) {

            releaseLocked()
        }
    }

    /*
     * ============================================================
     * 只释放当前 Track
     * ============================================================
     */
    fun releaseIfCurrent(
        track: AudioTrack?
    ) {

        if (
            track == null
        ) {

            return
        }

        synchronized(lock) {

            releaseIfCurrentLocked(
                track
            )
        }
    }

    /*
     * ============================================================
     * 是否拥有这个 Track
     * ============================================================
     */
    fun owns(
        track: AudioTrack?
    ): Boolean {

        if (
            track == null
        ) {

            return false
        }

        return synchronized(lock) {

            audioTrack === track
        }
    }

    /*
     * ============================================================
     * Track安全检查
     * ============================================================
     */
    private fun isTrackUsableLocked(
        track: AudioTrack
    ): Boolean {

        return try {

            track.state ==
                    AudioTrack.STATE_INITIALIZED

        } catch (
            _: Throwable
        ) {

            false
        }
    }

    /*
     * ============================================================
     * 安全释放
     * ============================================================
     */
    private fun releaseLocked() {

        val track =
            audioTrack

        audioTrack =
            null

        if (
            track == null
        ) {

            return
        }

        try {

            if (
                track.playState ==
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                track.pause()
            }

        } catch (_: Throwable) {
        }

        try {

            track.flush()

        } catch (_: Throwable) {
        }

        try {

            track.release()

        } catch (_: Throwable) {
        }

        logger(
            "$TAG: AudioTrack已彻底释放"
        )
    }

    /*
     * ============================================================
     * 只释放当前 Track
     * ============================================================
     */
    private fun releaseIfCurrentLocked(
        track: AudioTrack
    ) {

        if (
            audioTrack !==
            track
        ) {

            return
        }

        audioTrack =
            null

        try {

            if (
                track.playState ==
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                track.pause()
            }

        } catch (_: Throwable) {
        }

        try {

            track.flush()

        } catch (_: Throwable) {
        }

        try {

            track.release()

        } catch (_: Throwable) {
        }

        logger(
            "$TAG: 当前AudioTrack已失效并释放"
        )
    }

    /*
     * ============================================================
     * 内置扬声器
     * ============================================================
     *
     * 只在创建 AudioTrack 时调用。
     *
     * 不再每次 write() 前调用。
     */
    private fun setSpeakerLocked(
        track: AudioTrack
    ) {

        if (
            Build.VERSION.SDK_INT < 23
        ) {

            return
        }

        val audioManager =
            context.getSystemService(
                Context.AUDIO_SERVICE
            ) as? AudioManager
                ?: return

        val devices:
                Array<AudioDeviceInfo> =
            try {

                audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                )

            } catch (
                e: Throwable
            ) {

                logger(
                    "$TAG: 获取输出设备异常=${e.message}"
                )

                return
            }

        var speaker:
                AudioDeviceInfo? =
            null

        for (
        device in devices
        ) {

            if (
                device.type ==
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            ) {

                speaker =
                    device

                break
            }
        }

        if (
            speaker == null
        ) {

            return
        }

        try {

            track.setPreferredDevice(
                speaker
            )

            logger(
                "$TAG: 已设置内置扬声器"
            )

        } catch (
            e: Throwable
        ) {

            logger(
                "$TAG: setPreferredDevice异常=${e.message}"
            )
        }
    }

    /*
     * ============================================================
     * PCM增益
     * ============================================================
     */
    private fun applyGain(
        source: ByteArray
    ): ByteArray {

        if (
            gain == 1.0f
        ) {

            return source
        }

        if (
            source.size < 2
        ) {

            return source
        }

        val result =
            source.copyOf()

        var index =
            0

        while (
            index + 1 <
            result.size
        ) {

            val low =
                result[index]
                    .toInt()
                    .and(0xFF)

            val high =
                result[index + 1]
                    .toInt()

            val sampleInt =
                (
                        low or
                                (high shl 8)
                        ).toShort()
                    .toInt()

            val amplified =
                (
                        sampleInt.toFloat() *
                                gain
                        )
                    .coerceIn(
                        Short.MIN_VALUE.toFloat(),
                        Short.MAX_VALUE.toFloat()
                    )
                    .toInt()

            result[index] =
                (
                        amplified
                            .and(0xFF)
                        ).toByte()

            result[index + 1] =
                (
                        (amplified shr 8)
                            .and(0xFF)
                        ).toByte()

            index +=
                2
        }

        return result
    }
}