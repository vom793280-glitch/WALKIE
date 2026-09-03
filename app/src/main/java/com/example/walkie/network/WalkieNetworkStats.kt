package com.example.walkie.network

import android.content.Context
import android.content.Intent
import java.util.ArrayDeque
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

/**
 * WALKIE 网络质量统计模块。
 *
 * 负责：
 * 1. UDP Ping / Pong RTT
 * 2. 丢包率
 * 3. 抖动
 * 4. 上下行语音码率
 * 5. 网络质量分级
 * 6. 弱网播放恢复缓冲建议
 * 7. 网络状态广播
 *
 * 不负责建立或关闭 UDP Socket。Socket 生命周期仍由 WalkieService 管理。
 */
class WalkieNetworkStats(
    private val serverPort: Int,
    private val pingMessagePrefix: String,
    private val pongMessagePrefix: String,
    private val pingWindowSize: Int,
    private val pingTimeoutMs: Long,
    private val bitrateWindowMs: Long,
    private val statusMinIntervalMs: Long,
    private val defaultRecoveryPackets: Int,
    private val socketProvider: () -> DatagramSocket?,
    private val serverAddressProvider: () -> InetAddress?,
    private val isConnectedProvider: () -> Boolean,
    private val packageNameProvider: () -> String,
    private val context: Context,
    private val actionNetworkStatus: String,
    private val extraLatency: String,
    private val extraLoss: String,
    private val extraQuality: String,
    private val extraBitrate: String,
    private val extraUploadBitrate: String,
    private val extraDownloadBitrate: String,
    private val extraJitter: String,
    private val logger: (String) -> Unit = {}
) {

    private val lock = Any()

    private val pingPending =
        HashMap<Long, Long>()

    private val pingResults =
        ArrayDeque<Boolean>()

    @Volatile
    var pingSequence = 0L
        private set

    @Volatile
    var latencyMs = -1L
        private set

    @Volatile
    var jitterMs = -1L
        private set

    @Volatile
    var lossPercent = 0f
        private set

    @Volatile
    var quality = "检测中"
        private set

    @Volatile
    var uploadBitrateKbps = 0f
        private set

    @Volatile
    var downloadBitrateKbps = 0f
        private set

    @Volatile
    var adaptiveRecoveryPackets = defaultRecoveryPackets
        private set

    private var lastBroadcastTime = 0L

    private var txAudioWindowBytes = 0L
    private var txAudioWindowStart = 0L

    private var rxAudioWindowBytes = 0L
    private var rxAudioWindowStart = 0L

    fun reset(now: Long) {

        synchronized(lock) {
            pingPending.clear()
            pingResults.clear()
            pingSequence = 0L
        }

        latencyMs = -1L
        jitterMs = -1L
        lossPercent = 0f
        uploadBitrateKbps = 0f
        downloadBitrateKbps = 0f

        quality =
            if (isConnectedProvider()) {
                "检测中"
            } else {
                "离线"
            }

        txAudioWindowBytes = 0L
        txAudioWindowStart = now
        rxAudioWindowBytes = 0L
        rxAudioWindowStart = now

        adaptiveRecoveryPackets = defaultRecoveryPackets
        lastBroadcastTime = 0L

        broadcastStatus(true)
    }

    fun sendPing(now: Long) {

        val seq = synchronized(lock) {
            pingSequence =
                (pingSequence + 1L) and 0x7FFF_FFFFL
            pingPending[pingSequence] = now
            pingSequence
        }

        val socket = socketProvider()
        val address = serverAddressProvider()

        if (
            socket == null ||
            socket.isClosed ||
            address == null
        ) {

            synchronized(lock) {
                pingPending.remove(seq)
            }

            logger(
                "WALKIE: NET PING 发送失败：Socket不可用 seq=$seq"
            )

            addPingResult(false)
            updateQuality()
            broadcastStatus(false)
            return
        }

        try {

            val data =
                "$pingMessagePrefix:$seq:$now"
                    .toByteArray(Charsets.UTF_8)

            socket.send(
                DatagramPacket(
                    data,
                    data.size,
                    address,
                    serverPort
                )
            )

            logger(
                "WALKIE: NET PING 发送 seq=$seq"
            )

        } catch (e: Throwable) {

            synchronized(lock) {
                pingPending.remove(seq)
            }

            logger(
                "WALKIE: NET PING 发送异常 seq=$seq error=${e.message}"
            )

            addPingResult(false)
            updateQuality()
            broadcastStatus(false)
        }
    }

    fun handlePong(
        text: String,
        now: Long
    ) {

        val payload =
            text.substringAfter(
                "$pongMessagePrefix:",
                ""
            )

        val sequence =
            payload
                .split(":")
                .getOrNull(0)
                ?.toLongOrNull()
                ?: return

        val sentAt =
            synchronized(lock) {
                pingPending.remove(sequence)
            } ?: return

        val rtt =
            (now - sentAt).coerceIn(0L, 60000L)

        val previous = latencyMs

        latencyMs =
            if (previous < 0L) {
                rtt
            } else {
                ((previous * 0.70) + (rtt * 0.30)).toLong()
            }

        if (previous >= 0L) {

            val diff = abs(rtt - previous).toDouble()

            jitterMs =
                if (jitterMs < 0L) {
                    diff.toLong()
                } else {
                    ((jitterMs * 0.75) + (diff * 0.25)).toLong()
                }

        } else {
            jitterMs = 0L
        }

        addPingResult(true)
        updateQuality()
        adaptPlaybackBuffer()

        logger(
            "WALKIE: NET PONG seq=$sequence " +
                    "rtt=${rtt}ms " +
                    "loss=${lossPercent}% " +
                    "jitter=${jitterMs}ms"
        )

        broadcastStatus(false)
    }

    fun expirePings(now: Long) {

        var expiredCount = 0

        synchronized(lock) {
            val iterator = pingPending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value >= pingTimeoutMs) {
                    expiredCount++
                    iterator.remove()
                }
            }
        }

        if (expiredCount > 0) {
            repeat(expiredCount) {
                addPingResult(false)
            }

            updateQuality()
            adaptPlaybackBuffer()
            broadcastStatus(false)
        }
    }

    private fun addPingResult(success: Boolean) {

        synchronized(lock) {

            pingResults.addLast(success)

            while (pingResults.size > pingWindowSize) {
                pingResults.removeFirst()
            }

            val total = pingResults.size

            lossPercent =
                if (total <= 0) {
                    0f
                } else {
                    val lost = pingResults.count { !it }
                    lost * 100f / total.toFloat()
                }
        }
    }

    fun updateQuality() {

        val latency = latencyMs
        val loss = lossPercent
        val jitter = jitterMs.coerceAtLeast(0L)

        val sampleCount =
            synchronized(lock) { pingResults.size }

        quality =
            when {
                !isConnectedProvider() -> "离线"
                sampleCount < 3 || latency < 0L -> "检测中"
                loss >= 20f || latency >= 300L || jitter >= 100L -> "较差"
                loss >= 8f || latency >= 180L || jitter >= 50L -> "一般"
                loss >= 3f || latency >= 100L || jitter >= 25L -> "良好"
                else -> "优秀"
            }
    }

    fun adaptPlaybackBuffer() {

        val loss = lossPercent
        val latency = latencyMs
        val jitter = jitterMs

        adaptiveRecoveryPackets =
            when {
                loss >= 12f || latency >= 250L || jitter >= 70L -> 6
                loss >= 5f || latency >= 150L || jitter >= 35L -> 4
                else -> defaultRecoveryPackets
            }.coerceIn(3, 6)
    }

    fun recordAudioTransmit(bytes: Int) {

        val now = System.currentTimeMillis()

        if (txAudioWindowStart <= 0L) {
            txAudioWindowStart = now
        }

        txAudioWindowBytes += bytes.toLong()
        updateBitrate(now)
    }

    fun recordAudioReceive(bytes: Int) {

        val now = System.currentTimeMillis()

        if (rxAudioWindowStart <= 0L) {
            rxAudioWindowStart = now
        }

        rxAudioWindowBytes += bytes.toLong()
        updateBitrate(now)
    }

    fun updateBitrate(now: Long) {

        val txStart = txAudioWindowStart
        val rxStart = rxAudioWindowStart

        val windowStart = minOf(
            if (txStart > 0L) txStart else now,
            if (rxStart > 0L) rxStart else now
        )

        if (now - windowStart < bitrateWindowMs) {
            return
        }

        if (txStart > 0L) {
            val elapsed = (now - txStart).coerceAtLeast(1L)
            uploadBitrateKbps =
                (txAudioWindowBytes * 8.0 /
                        (elapsed / 1000.0) /
                        1000.0).toFloat()
            txAudioWindowBytes = 0L
            txAudioWindowStart = now
        }

        if (rxStart > 0L) {
            val elapsed = (now - rxStart).coerceAtLeast(1L)
            downloadBitrateKbps =
                (rxAudioWindowBytes * 8.0 /
                        (elapsed / 1000.0) /
                        1000.0).toFloat()
            rxAudioWindowBytes = 0L
            rxAudioWindowStart = now
        }

        broadcastStatus(false)
    }

    fun broadcastStatus(force: Boolean) {

        val now = System.currentTimeMillis()

        if (
            !force &&
            now - lastBroadcastTime < statusMinIntervalMs
        ) {
            return
        }

        lastBroadcastTime = now

        val intent =
            Intent(actionNetworkStatus)
                .setPackage(packageNameProvider())
                .putExtra(extraLatency, latencyMs)
                .putExtra(extraLoss, lossPercent)
                .putExtra(extraQuality, quality)
                .putExtra(extraBitrate, uploadBitrateKbps)
                .putExtra(extraUploadBitrate, uploadBitrateKbps)
                .putExtra(extraDownloadBitrate, downloadBitrateKbps)
                .putExtra(extraJitter, jitterMs)

        context.sendBroadcast(intent)
    }
}