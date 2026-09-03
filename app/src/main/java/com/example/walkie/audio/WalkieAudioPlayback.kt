package com.example.walkie.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue

/**
 * WALKIE 音频播放调度器
 *
 * V24.9.2
 *
 * 负责：
 *
 * 1. PCM播放队列
 * 2. 首次播放缓冲
 * 3. 弱网恢复
 * 4. 播放线程
 * 5. underrun恢复
 * 6. 根据网络状态动态调整队列
 *
 * 不负责：
 *
 * 1. AudioTrack底层创建
 * 2. UDP
 * 3. Opus
 * 4. 网络连接
 * 5. PTT
 */
class WalkieAudioPlayback(
    private val audioPlayer: WalkieAudioPlayer,
    private val queueCapacity: Int = 40,
    private val startBufferPackets: Int = 3,
    private val recoveryBufferPackets: Int = 3,
    private val maxQueuePackets: Int = 24,
    private val latencyProvider: () -> Long = { -1L },
    private val lossProvider: () -> Float = { 100f },
    private val jitterProvider: () -> Long = { -1L },
    private val recoveryPacketsProvider: () -> Int = {
        recoveryBufferPackets
    },
    private val logger: (String) -> Unit = {}
) {

    companion object {

        /*
         * ============================================================
         * V24.9.2 音频真实时间轴
         * ============================================================
         *
         * 当前WALKIE：
         *
         * 16000Hz
         * 16bit
         * mono
         *
         * 每个PCM帧：
         *
         * 320 samples
         * 640 bytes
         *
         * = 20ms音频。
         *
         * 因此播放器正常情况下应该按照约20ms/帧
         * 的速度消费PCM。
         */
        private const val FRAME_DURATION_MS =
            20L

        /*
         * 队列为空时避免高速空转。
         */
        private const val EMPTY_QUEUE_DELAY_MS =
            3L

        /*
         * 恢复失败后稍等一小段时间。
         */
        private const val RECOVERY_RETRY_DELAY_MS =
            30L

        /*
         * ============================================================
         * V24.9.2：
         *
         * AudioTrack真正发生underrun以后，
         * 至少等待8帧PCM重新进入。
         *
         * 8帧 × 20ms = 160ms
         *
         * 原来是3帧 = 60ms，
         * 网络恢复后缓冲太浅，容易再次underrun。
         */
        private const val MIN_RECOVERY_PACKETS =
            8
    }

    /*
     * ============================================================
     * 播放协程
     * ============================================================
     */

    private val playbackScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    /*
     * ============================================================
     * PCM队列
     * ============================================================
     *
     * 每一个元素：
     *
     * 640 bytes = 20ms PCM
     */
    private val playbackQueue =
        ArrayBlockingQueue<ByteArray>(
            queueCapacity
        )

    /*
     * ============================================================
     * Worker状态
     * ============================================================
     */

    private val workerLock =
        Any()

    private var playbackJob:
            Job? =
        null

    private var workerStarting =
        false

    @Volatile
    private var recoveryRequested =
        true

    private var firstStart =
        true

    private var lastUnderrunCount =
        0

    /*
     * ============================================================
     * 加入PCM
     * ============================================================
     */

    fun enqueue(
        data: ByteArray
    ) {

        if (
            data.isEmpty()
        ) {
            return
        }

        if (
            data.size % 2 != 0
        ) {

            logger(
                "WALKIE AUDIO PLAYBACK: " +
                        "PCM长度为奇数，丢弃=${data.size}"
            )

            return
        }

        if (
            data.size > 8192
        ) {

            logger(
                "WALKIE AUDIO PLAYBACK: " +
                        "PCM过大，丢弃=${data.size}"
            )

            return
        }

        /*
         * ========================================================
         * 动态最大队列
         * ========================================================
         */

        val latency =
            latencyProvider()

        val loss =
            lossProvider()

        val jitter =
            jitterProvider()

        val dynamicMaxQueue =
            when {

                loss >= 15f ||
                        latency >= 300L ||
                        jitter >= 100L ->
                    20

                loss >= 8f ||
                        latency >= 180L ||
                        jitter >= 60L ->
                    16

                loss >= 3f ||
                        latency >= 100L ||
                        jitter >= 35L ->
                    12

                else ->
                    8
            }.coerceIn(
                8,
                maxQueuePackets
            )

        /*
         * 超出实时范围时，
         * 丢弃最老帧。
         */
        while (
            playbackQueue.size >=
            dynamicMaxQueue
        ) {

            playbackQueue.poll()
        }

        playbackQueue.offer(
            data
        )

        /*
         * Track不存在时，
         * 请求恢复。
         */
        if (
            !audioPlayer.isReady()
        ) {

            recoveryRequested =
                true
        }

        startWorker()
    }

    /*
     * ============================================================
     * 请求恢复
     * ============================================================
     */

    fun requestRecovery() {

        recoveryRequested =
            true

        startWorker()
    }

    /*
     * ============================================================
     * 队列数量
     * ============================================================
     */

    fun queueSize(): Int {

        return playbackQueue.size
    }

    /*
     * ============================================================
     * 播放状态
     * ============================================================
     */

    fun isPlaying(): Boolean {

        return audioPlayer.getPlayState() ==
                android.media.AudioTrack.PLAYSTATE_PLAYING
    }

    /*
     * ============================================================
     * AudioTrack状态
     * ============================================================
     */

    fun isReady(): Boolean {

        return audioPlayer.isReady()
    }

    /*
     * ============================================================
     * underrun
     * ============================================================
     */

    fun getUnderrunCount(): Int {

        return audioPlayer.getUnderrunCount()
    }

    /*
     * ============================================================
     * 清空队列
     * ============================================================
     */

    fun clearQueue() {

        playbackQueue.clear()

        recoveryRequested =
            true

        firstStart =
            true

        lastUnderrunCount =
            0
    }

    /*
     * ============================================================
     * 释放播放资源
     * ============================================================
     */

    fun release() {

        synchronized(workerLock) {

            playbackJob?.cancel()

            playbackJob =
                null

            workerStarting =
                false
        }

        playbackQueue.clear()

        audioPlayer.release()

        recoveryRequested =
            true

        firstStart =
            true

        lastUnderrunCount =
            0
    }

    /*
     * ============================================================
     * 完整关闭
     * ============================================================
     */

    fun shutdown() {

        synchronized(workerLock) {

            playbackJob?.cancel()

            playbackJob =
                null

            workerStarting =
                false
        }

        playbackQueue.clear()

        audioPlayer.release()

        recoveryRequested =
            true

        firstStart =
            true

        lastUnderrunCount =
            0

        playbackScope.cancel()
    }

    /*
     * ============================================================
     * 启动Worker
     * ============================================================
     */

    private fun startWorker() {

        synchronized(workerLock) {

            if (
                !playbackScope.isActive
            ) {

                return
            }

            val currentJob =
                playbackJob

            if (
                currentJob != null &&
                currentJob.isActive
            ) {

                return
            }

            if (
                workerStarting
            ) {

                return
            }

            workerStarting =
                true

            playbackJob =
                playbackScope.launch {

                    try {

                        playbackLoop()

                    } catch (
                        throwable: Throwable
                    ) {

                        if (
                            playbackScope.isActive
                        ) {

                            logger(
                                "WALKIE AUDIO PLAYBACK: " +
                                        "Worker异常=" +
                                        throwable.message
                            )
                        }

                    } finally {

                        synchronized(workerLock) {

                            workerStarting =
                                false

                            playbackJob =
                                null
                        }
                    }
                }
        }
    }

    /*
     * ============================================================
     * 播放主循环
     * ============================================================
     */

    private suspend fun playbackLoop() {

        while (
            playbackScope.isActive
        ) {

            val recovering =
                recoveryRequested

            val recoveryTarget =
                recoveryPacketsProvider()
                    .coerceIn(
                        MIN_RECOVERY_PACKETS,
                        maxQueuePackets
                    )

            /*
             * ========================================================
             * 首次播放
             * ========================================================
             *
             * 网络恢复后至少准备8帧，
             * 再启动连续播放。
             *
             * 8帧 × 20ms = 160ms。
             *
             * 这样可以比原来的60ms提供更大的
             * AudioTrack安全余量。
             */
            val requiredPackets =
                when {

                    firstStart ->
                        maxOf(
                            MIN_RECOVERY_PACKETS,
                            startBufferPackets
                        ).coerceAtMost(
                            maxQueuePackets
                        )

                    recovering ->
                        recoveryTarget

                    else ->
                        1
                }

            if (
                playbackQueue.size <
                requiredPackets
            ) {

                if (
                    firstStart ||
                    recovering
                ) {

                    delay(4L)

                } else {

                    delay(
                        EMPTY_QUEUE_DELAY_MS
                    )
                }

                continue
            }

            if (
                !playbackScope.isActive
            ) {

                break
            }

            /*
             * ========================================================
             * 确保AudioTrack
             * ========================================================
             */

            if (
                !audioPlayer.ensureAudioPlayer()
            ) {

                recoveryRequested =
                    true

                delay(
                    RECOVERY_RETRY_DELAY_MS
                )

                continue
            }

            if (
                firstStart ||
                recoveryRequested
            ) {

                playRecovery(
                    requiredPackets
                )

            } else {

                playNormal()
            }
        }
    }

    /*
     * ============================================================
     * 恢复播放
     * ============================================================
     *
     * V24.9.2核心：
     *
     * 恢复阶段必须快速预填AudioTrack。
     *
     * 不能：
     *
     * 第1帧
     * ↓
     * 等20ms
     * ↓
     * 第2帧
     * ↓
     * 等20ms
     *
     * 因为AudioTrack已经在播放第一帧，
     * 慢慢填充会造成再次underrun。
     *
     * 正确方式：
     *
     * 第1帧
     * ↓
     * 第2帧
     * ↓
     * 第3帧
     * ↓
     * ...
     * ↓
     * 第8帧
     *
     * 快速写入AudioTrack。
     *
     * AudioTrack自身负责真实20ms播放时钟。
     */
    private suspend fun playRecovery(
        requiredPackets: Int
    ) {

        val recoveryLimit =
            (
                    requiredPackets +
                            2
                    ).coerceAtMost(
                    maxQueuePackets
                )

        /*
         * 如果积压太多，
         * 保留最新实时声音。
         */
        while (
            playbackQueue.size >
            recoveryLimit
        ) {

            playbackQueue.poll()
        }

        var playedPackets =
            0

        /*
         * ========================================================
         * V24.9.2：
         *
         * 快速预填，不再等待20ms。
         * ========================================================
         */
        while (
            playedPackets <
            requiredPackets &&
            playbackScope.isActive
        ) {

            val frame =
                playbackQueue.poll()

            if (
                frame == null
            ) {

                break
            }

            val success =
                audioPlayer.writeAndPlay(
                    frame
                )

            if (
                !success
            ) {

                requeueFront(
                    listOf(
                        frame
                    )
                )

                recoveryRequested =
                    true

                delay(
                    RECOVERY_RETRY_DELAY_MS
                )

                return
            }

            playedPackets++
        }

        if (
            playedPackets <= 0
        ) {

            recoveryRequested =
                true

            return
        }

        /*
         * ========================================================
         * 恢复完成
         * ========================================================
         *
         * 必须至少达到8帧。
         */
        if (
            playedPackets >=
            MIN_RECOVERY_PACKETS
        ) {

            recoveryRequested =
                false

            firstStart =
                false

            lastUnderrunCount =
                audioPlayer.getUnderrunCount()

            logger(
                "WALKIE AUDIO PLAYBACK: " +
                        "恢复播放完成 packets=$playedPackets " +
                        "queue=${playbackQueue.size}"
            )

        } else {

            /*
             * 不足8帧，
             * 保持恢复状态。
             */
            recoveryRequested =
                true

            logger(
                "WALKIE AUDIO PLAYBACK: " +
                        "恢复播放暂未完成 packets=$playedPackets " +
                        "queue=${playbackQueue.size}"
            )
        }
    }

    /*
     * ============================================================
     * 正常播放
     * ============================================================
     *
     * 每一个PCM帧 = 20ms
     *
     * 因此：
     *
     * write
     * ↓
     * 20ms
     * ↓
     * write下一帧
     */

    private suspend fun playNormal() {

        val frame =
            playbackQueue.poll()

        if (
            frame == null
        ) {

            return
        }

        if (
            !playbackScope.isActive
        ) {

            requeueFront(
                listOf(
                    frame
                )
            )

            return
        }

        val success =
            audioPlayer.writeAndPlay(
                frame
            )

        if (
            !success
        ) {

            requeueFront(
                listOf(
                    frame
                )
            )

            recoveryRequested =
                true

            delay(
                RECOVERY_RETRY_DELAY_MS
            )

            return
        }

        /*
         * ========================================================
         * 检测真实underrun
         * ========================================================
         */

        val currentUnderrun =
            audioPlayer.getUnderrunCount()

        if (
            currentUnderrun >
            lastUnderrunCount
        ) {

            lastUnderrunCount =
                currentUnderrun

            recoveryRequested =
                true

            logger(
                "WALKIE AUDIO PLAYBACK: " +
                        "检测到underrun=" +
                        currentUnderrun +
                        "，进入恢复"
            )

            return
        }

        lastUnderrunCount =
            currentUnderrun

        firstStart =
            false

        /*
         * ========================================================
         * V24.9.2：
         *
         * 这里不再 delay(20ms)。
         *
         * AudioTrack.writeAndPlay() 内部使用
         * WRITE_BLOCKING，由 AudioTrack 自己负责
         * 底层播放节奏。
         *
         * 人工再加20ms会变成：
         *
         * write耗时 + 20ms
         *
         * 导致实际消费速度慢于20ms/帧。
         * ========================================================
         */
    }

    private fun requeueFront(
        frames: List<ByteArray>
    ) {

        if (
            frames.isEmpty()
        ) {

            return
        }

        val existing =
            ArrayList<ByteArray>(
                playbackQueue.size
            )

        playbackQueue.drainTo(
            existing
        )

        playbackQueue.clear()

        /*
         * 失败帧优先。
         */
        for (
        frame in frames
        ) {

            if (
                playbackQueue.size >=
                queueCapacity
            ) {

                break
            }

            playbackQueue.offer(
                frame
            )
        }

        /*
         * 原有队列继续保留。
         */
        for (
        frame in existing
        ) {

            if (
                playbackQueue.size >=
                queueCapacity
            ) {

                break
            }

            playbackQueue.offer(
                frame
            )
        }
    }
}