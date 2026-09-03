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
 * V24.9.1
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
     * 每一个元素保持一个完整20ms PCM帧。
     *
     * 640 bytes =
     * 16000Hz / 50 =
     * 320 samples =
     * 640 bytes
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
         * 根据当前网络状态动态决定最大队列长度
         * ========================================================
         *
         * 网络越差：
         *
         *     允许更多缓存
         *
         * 网络越好：
         *
         *     保持更低延迟
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
         * ========================================================
         * 队列过长：
         *
         * 丢弃最老的PCM帧。
         *
         * 保证实时性。
         * ========================================================
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
     * 启动播放Worker
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
                        1,
                        maxQueuePackets
                    )

            /*
             * ========================================================
             * 当前需要的启动缓存
             * ========================================================
             *
             * V24.9.1：
             *
             * 首次播放不再强制等待3包。
             *
             * 收到第一帧有效PCM后即可开始播放。
             *
             * 这样可以避免：
             *
             * 鸿蒙 -> Android
             *
             * 在不同发送节奏下，
             * 因为启动缓存不足而长时间无法开始播放。
             * ========================================================
             */

            val requiredPackets =
                when {

                    /*
                     * 首次播放：
                     *
                     * 只需要1包PCM即可启动。
                     */
                    firstStart ->
                        1

                    /*
                     * 播放恢复：
                     *
                     * 仍然使用动态恢复缓冲策略。
                     */
                    recovering ->
                        recoveryTarget

                    /*
                     * 正常播放：
                     *
                     * 保持1包实时播放。
                     */
                    else ->
                        1
                }

            /*
             * ========================================================
             * 缓冲不足
             * ========================================================
             */

            if (
                playbackQueue.size <
                requiredPackets
            ) {

                if (
                    recovering ||
                    firstStart
                ) {

                    delay(4L)

                } else {

                    delay(8L)
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
             * 确保 AudioTrack
             * ========================================================
             */

            if (
                !audioPlayer.ensureAudioPlayer()
            ) {

                recoveryRequested =
                    true

                delay(30L)

                continue
            }

            /*
             * ========================================================
             * 首次播放 / 恢复播放
             * ========================================================
             */

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
     * 重要：
     *
     * 不再把多个20ms PCM帧合并成40ms/60ms大块。
     *
     * 保持：
     *
     * 20ms
     * ↓
     * AudioTrack.write()
     *
     * 20ms
     * ↓
     * AudioTrack.write()
     *
     * 20ms
     * ↓
     * AudioTrack.write()
     *
     * 这样声音时间轴最稳定。
     * ============================================================
     */

    private suspend fun playRecovery(
        requiredPackets: Int
    ) {

        val recoveryLimit =
            (
                    requiredPackets +
                            2
                    ).coerceAtMost(
                    recoveryPacketsProvider()
                        .coerceAtLeast(1) +
                            2
                )

        /*
         * ========================================================
         * 队列异常过长：
         *
         * 丢弃最老帧。
         * ========================================================
         */

        while (
            playbackQueue.size >
            recoveryLimit
        ) {

            playbackQueue.poll()
        }

        /*
         * ========================================================
         * 一帧一帧播放
         * ========================================================
         */

        var playedPackets =
            0

        while (
            playedPackets <
            requiredPackets
        ) {

            if (
                !playbackScope.isActive
            ) {
                return
            }

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

                /*
                 * 当前帧播放失败，
                 * 放回最前面。
                 */

                requeueFront(
                    listOf(frame)
                )

                recoveryRequested =
                    true

                delay(30L)

                return
            }

            playedPackets++
        }

        /*
         * 如果一包都没成功，
         * 保持恢复状态。
         */

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
         */

        recoveryRequested =
            false

        firstStart =
            false

        lastUnderrunCount =
            audioPlayer.getUnderrunCount()
    }

    /*
     * ============================================================
     * 正常播放
     * ============================================================
     *
     * 正常情况下：
     *
     * 一个队列元素 =
     * 一个20ms PCM帧
     *
     * 每次只取一个。
     *
     * ============================================================
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
                listOf(frame)
            )
            return
        }

        /*
         * ========================================================
         * 直接播放一个20ms帧
         * ========================================================
         */

        val success =
            audioPlayer.writeAndPlay(
                frame
            )

        if (
            !success
        ) {

            requeueFront(
                listOf(frame)
            )

            recoveryRequested =
                true

            delay(25L)

            return
        }

        /*
         * ========================================================
         * underrun检测
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

        /*
         * ========================================================
         * 队列不足：
         *
         * 提前进入恢复模式。
         * ========================================================
         */

        val recoveryTarget =
            recoveryPacketsProvider()
                .coerceAtLeast(1)

        if (
            playbackQueue.size <
            recoveryTarget
        ) {

            recoveryRequested =
                true

        } else {

            recoveryRequested =
                false
        }

        firstStart =
            false
    }

    /*
     * ============================================================
     * 将失败帧放回队列最前面
     * ============================================================
     */

    private fun requeueFront(
        frames: List<ByteArray>
    ) {

        if (
            frames.isEmpty()
        ) {
            return
        }

        /*
         * 先保存原队列。
         */

        val existing =
            ArrayList<ByteArray>(
                playbackQueue.size
            )

        playbackQueue.drainTo(
            existing
        )

        playbackQueue.clear()

        /*
         * ========================================================
         * 失败帧优先
         * ========================================================
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
         * ========================================================
         * 再恢复原队列
         * ========================================================
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