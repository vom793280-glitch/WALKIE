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

        /*
         * ============================================================
         * V24.9.1 音频设备切换保护
         * ============================================================
         *
         * Android 在：
         *
         * WiFi / 移动网络变化
         * 系统音频设备切换
         * 蓝牙设备变化
         * 锁屏/解锁
         *
         * 过程中，AudioTrack.write() 可能短暂返回 0。
         *
         * 这个 0 不一定意味着 AudioTrack 已经死亡。
         *
         * 因此：
         *
         * result < 0
         *      ↓
         * 才按照真正错误处理
         *
         * result == 0
         *      ↓
         * 短暂等待
         *      ↓
         * 重试
         *
         * 防止：
         *
         * write=0
         * ↓
         * release
         * ↓
         * create
         * ↓
         * write=0
         * ↓
         * release
         *
         * 形成无限AudioTrack重建循环。
         * ============================================================
         */

        private const val MAX_ZERO_WRITE_RETRY =
            5

        private const val ZERO_WRITE_RETRY_DELAY_MS =
            12L

        private const val MAX_SHORT_WRITE_RETRY =
            3

        /*
         * 创建Track以后，
         * 稍微给Android音频服务一点时间完成路由稳定。
         */
        private const val TRACK_START_DELAY_MS =
            10L
    }

    /*
     * ============================================================
     * AudioTrack 生命周期锁
     * ============================================================
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
         * V24.9.1：
         * 创建Track以后才进行一次初始扬声器路由。
         *
         * 不在每次write()前操作设备路由。
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
         * 给Android音频服务一个极短的时间完成Track注册。
         *
         * 特别是系统正在做device switching时，
         * 立即write可能出现result=0。
         */
        try {

            Thread.sleep(
                TRACK_START_DELAY_MS
            )

        } catch (_: InterruptedException) {

            Thread.currentThread().interrupt()
        }

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
             * 当前Track不存在或已经明显无效
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

            val currentTrack =
                track
                    ?: return false

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
             * ========================================================
             * V24.9.1关键修复：
             *
             * write()==0
             *
             * 不再立刻release AudioTrack。
             *
             * Android在device switching时可能暂时返回0。
             *
             * 所以给当前Track几次机会。
             * ========================================================
             */

            var totalWritten =
                0

            var zeroRetryCount =
                0

            var shortRetryCount =
                0

            while (
                totalWritten <
                data.size
            ) {

                val result =
                    try {

                        currentTrack.write(
                            data,
                            totalWritten,
                            data.size -
                                    totalWritten,
                            AudioTrack.WRITE_BLOCKING
                        )

                    } catch (
                        e: Throwable
                    ) {

                        logger(
                            "$TAG: write异常=${e.message}"
                        )

                        releaseIfCurrentLocked(
                            currentTrack
                        )

                        return false
                    }

                /*
                 * ====================================================
                 * 真正的致命AudioTrack错误
                 * ====================================================
                 */
                if (
                    result <
                    0
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
                 * ====================================================
                 * result == 0
                 *
                 * 临时没有写进去。
                 *
                 * 特别是Android正在：
                 *
                 * creating an audioTrack during device switching
                 *
                 * 时不能立刻把Track杀掉。
                 * ====================================================
                 */
                if (
                    result == 0
                ) {

                    zeroRetryCount++

                    logger(
                        "$TAG: AudioTrack短写 result=0 " +
                                "retry=$zeroRetryCount/" +
                                MAX_ZERO_WRITE_RETRY
                    )

                    if (
                        zeroRetryCount >=
                        MAX_ZERO_WRITE_RETRY
                    ) {

                        /*
                         * =================================================
                         * 多次0仍然无法恢复。
                         *
                         * 这里先返回false。
                         *
                         * 注意：
                         *
                         * 不释放当前Track。
                         *
                         * 让下一份PCM再次进入时继续尝试，
                         * 避免进入“创建 -> 失效 -> 创建”的死循环。
                         * =================================================
                         */
                        logger(
                            "$TAG: AudioTrack连续短写0，" +
                                    "暂不释放Track，等待下一包PCM恢复"
                        )

                        return false
                    }

                    try {

                        Thread.sleep(
                            ZERO_WRITE_RETRY_DELAY_MS
                        )

                    } catch (
                        _: InterruptedException
                    ) {

                        Thread.currentThread().interrupt()

                        return false
                    }

                    continue
                }

                /*
                 * ====================================================
                 * 正常写入了一部分。
                 *
                 * 继续写剩余数据。
                 * ====================================================
                 */
                totalWritten +=
                    result

                if (
                    result <
                    data.size -
                    totalWritten +
                    result
                ) {

                    shortRetryCount++

                    if (
                        shortRetryCount >
                        MAX_SHORT_WRITE_RETRY
                    ) {

                        logger(
                            "$TAG: AudioTrack持续短写，" +
                                    "written=$totalWritten/" +
                                    data.size
                        )

                        return false
                    }
                } else {

                    shortRetryCount =
                        0
                }

                zeroRetryCount =
                    0
            }

            /*
             * ========================================================
             * 写入成功后再启动播放。
             * ========================================================
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

            return true
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

            val data =
                applyGain(
                    pcmData
                )

            var written =
                0

            var retryCount =
                0

            while (
                written <
                data.size
            ) {

                val result =
                    try {

                        track.write(
                            data,
                            written,
                            data.size -
                                    written,
                            AudioTrack.WRITE_BLOCKING
                        )

                    } catch (
                        e: Throwable
                    ) {

                        logger(
                            "$TAG: write异常=${e.message}"
                        )

                        releaseIfCurrentLocked(
                            track
                        )

                        return false
                    }

                if (
                    result <
                    0
                ) {

                    logger(
                        "$TAG: write错误=$result"
                    )

                    releaseIfCurrentLocked(
                        track
                    )

                    return false
                }

                if (
                    result == 0
                ) {

                    retryCount++

                    if (
                        retryCount >=
                        MAX_ZERO_WRITE_RETRY
                    ) {

                        logger(
                            "$TAG: write连续返回0，" +
                                    "暂不释放Track"
                        )

                        return false
                    }

                    try {

                        Thread.sleep(
                            ZERO_WRITE_RETRY_DELAY_MS
                        )

                    } catch (
                        _: InterruptedException
                    ) {

                        Thread.currentThread().interrupt()

                        return false
                    }

                    continue
                }

                written +=
                    result

                retryCount =
                    0
            }

            return true
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
     */
    private fun setSpeakerLocked(
        track: AudioTrack
    ) {

        if (
            Build.VERSION.SDK_INT <
            23
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