package com.example.walkie

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException

class WalkieConnectionManager(
    private val scope: CoroutineScope,
    private val udpManager: WalkieUdpManager,
    private val logger: (String) -> Unit
) {

    val connectionLock =
        Any()

    var connectionGeneration =
        0L

    var networkJob: Job? =
        null

    fun isConnectionGenerationCurrent(
        generation: Long
    ): Boolean {

        return synchronized(
            connectionLock
        ) {

            connectionGeneration ==
                    generation
        }
    }

    fun startConnection(
        ip: String,
        setServerIp: (String) -> Unit,
        isShuttingDown: () -> Boolean,
        isConnected: () -> Boolean,
        isUdpOpen: () -> Boolean,
        runConnectionLoop: suspend (
            String,
            Long
        ) -> Unit
    ) {

        val cleanIp =
            ip.trim()

        if (
            cleanIp.isBlank()
        ) {

            return
        }

        synchronized(
            connectionLock
        ) {

            setServerIp(
                cleanIp
            )

            if (
                networkJob?.isActive ==
                true
            ) {

                if (
                    !isConnected() &&
                    !isUdpOpen()
                ) {

                    logger(
                        "检测到旧连接任务仍在退出，" +
                                "立即取消旧任务并启动新一代连接"
                    )

                    networkJob?.cancel()

                    networkJob =
                        null

                } else {

                    logger(
                        "连接任务已存在且连接仍有效，" +
                                "忽略重复连接请求"
                    )

                    return
                }
            }

            val generation =
                connectionGeneration + 1L

            connectionGeneration =
                generation

            logger(
                "启动唯一连接任务 " +
                        "generation=$generation " +
                        "$cleanIp"
            )

            networkJob =
                scope.launch {

                    runConnectionLoop(
                        cleanIp,
                        generation
                    )

                    synchronized(
                        connectionLock
                    ) {

                        if (
                            connectionGeneration ==
                            generation
                        ) {

                            networkJob =
                                null
                        }
                    }

                    logger(
                        "连接任务结束 " +
                                "generation=$generation"
                    )
                }
        }
    }

    suspend fun runConnectionLoop(
        ip: String,
        generation: Long,
        isShuttingDown: () -> Boolean,
        isNetworkAvailable: () -> Boolean,
        isUdpOpen: () -> Boolean,
        isConnected: () -> Boolean,
        isConnectionGenerationCurrent: (Long) -> Boolean,
        cleanupConnection: suspend (Long) -> Unit,
        connectOnce: suspend (
            String,
            Long
        ) -> Unit
    ) {

        var reconnectDelay =
            100L

        while (
            scope.isActive &&
            !isShuttingDown() &&
            isConnectionGenerationCurrent(
                generation
            )
        ) {

            var connectionSucceeded =
                false

            try {

                connectOnce(
                    ip,
                    generation
                )

                connectionSucceeded =
                    isUdpOpen() &&
                            isConnected() &&
                            isConnectionGenerationCurrent(
                                generation
                            )

                if (
                    connectionSucceeded
                ) {

                    logger(
                        "connectOnce正常返回，" +
                                "当前连接仍然有效 " +
                                "generation=$generation"
                    )

                    reconnectDelay =
                        100L

                    delay(
                        100L
                    )
                }

            } catch (
                e: SocketTimeoutException
            ) {

                if (
                    !isShuttingDown() &&
                    isConnectionGenerationCurrent(
                        generation
                    )
                ) {

                    logger(
                        "网络接收超时=${e.message} " +
                                "generation=$generation"
                    )
                }

            } catch (
                e: SocketException
            ) {

                if (
                    !isShuttingDown() &&
                    isConnectionGenerationCurrent(
                        generation
                    )
                ) {

                    logger(
                        "网络Socket异常=${e.message} " +
                                "generation=$generation"
                    )
                }

            } catch (
                e: Exception
            ) {

                if (
                    !isShuttingDown() &&
                    isConnectionGenerationCurrent(
                        generation
                    )
                ) {

                    logger(
                        "网络异常=${e.message} " +
                                "generation=$generation"
                    )
                }
            }

            if (
                !scope.isActive ||
                isShuttingDown() ||
                !isConnectionGenerationCurrent(
                    generation
                )
            ) {

                break
            }

            if (
                !connectionSucceeded
            ) {

                cleanupConnection(
                    generation
                )
            }

            if (
                !isNetworkAvailable()
            ) {

                delay(
                    1000L
                )

                continue
            }

            if (
                !isUdpOpen()
            ) {

                logger(
                    "UDP Manager无有效Socket，" +
                            "准备自动重连 " +
                            "generation=$generation"
                )

                delay(
                    reconnectDelay
                )

                if (
                    !isConnectionGenerationCurrent(
                        generation
                    )
                ) {

                    break
                }

                reconnectDelay =
                    if (
                        reconnectDelay * 2L >
                        500L
                    ) {

                        500L

                    } else {

                        reconnectDelay * 2L
                    }

                continue
            }

            reconnectDelay =
                100L

            delay(
                100L
            )
        }
    }

    suspend fun prepareSocket(
        ip: String,
        port: Int,
        network: Network?,
        generation: Long,
        isShuttingDown: () -> Boolean,
        isGenerationCurrent: (Long) -> Boolean,
        setCurrentSocket: (
            DatagramSocket
        ) -> Unit,
        onSocketOpened: (
            DatagramSocket
        ) -> Unit
    ): DatagramSocket? {

        if (
            !isGenerationCurrent(
                generation
            ) ||
            isShuttingDown()
        ) {

            return null
        }

        return try {

            /*
             * ★★★ 关键修复 ★★★
             *
             * 这里使用的是 WalkieService
             * 传进来的唯一 udpManager。
             *
             * 不再自己创建第二个 Manager。
             */
            udpManager.open(
                ip = ip,
                port = port,
                network = network
            )

            val socket =
                udpManager.currentSocket()
                    ?: run {

                        logger(
                            "UDP Manager创建Socket失败"
                        )

                        return null
                    }

            if (
                socket.isClosed
            ) {

                logger(
                    "UDP Manager创建Socket失败"
                )

                return null
            }

            if (
                !isGenerationCurrent(
                    generation
                ) ||
                isShuttingDown()
            ) {

                try {
                    udpManager.close()
                } catch (_: Exception) {
                }

                return null
            }

            try {

                socket.soTimeout =
                    100

            } catch (_: Exception) {
            }

            try {

                socket.receiveBufferSize =
                    128 * 1024

            } catch (_: Exception) {
            }

            try {

                socket.sendBufferSize =
                    64 * 1024

            } catch (_: Exception) {
            }

            synchronized(
                connectionLock
            ) {

                if (
                    connectionGeneration !=
                    generation ||
                    isShuttingDown()
                ) {

                    try {
                        udpManager.close()
                    } catch (_: Exception) {
                    }

                    return null
                }

                setCurrentSocket(
                    socket
                )
            }

            onSocketOpened(
                socket
            )

            logger(
                "UDP Manager创建Socket成功 " +
                        "localPort=${socket.localPort} " +
                        "generation=$generation"
            )

            socket

        } catch (
            e: Throwable
        ) {

            logger(
                "准备UDP Socket失败=${e.message}"
            )

            null
        }
    }

    fun performNetworkMaintenance(
        currentTime: Long,
        isConnected: () -> Boolean,
        lastNetworkPingTime: () -> Long,
        setLastNetworkPingTime: (
            Long
        ) -> Unit,
        networkPingInterval: Long,
        sendNetworkPing: (
            Long
        ) -> Unit,
        expireNetworkPings: (
            Long
        ) -> Unit,
        updateNetworkBitrate: (
            Long
        ) -> Unit,
        lastKeepAliveTime: () -> Long,
        setLastKeepAliveTime: (
            Long
        ) -> Unit,
        keepAliveInterval: Long,
        sendKeepAlive: () -> Unit,
        onKeepAliveSent: () -> Unit,
        lastServerActivityTime: () -> Long,
        serverActivityTimeout: Long,
        onServerTimeout: () -> Unit
    ): Boolean {

        expireNetworkPings(
            currentTime
        )

        if (
            isConnected() &&
            currentTime -
            lastNetworkPingTime() >=
            networkPingInterval
        ) {

            sendNetworkPing(
                currentTime
            )

            setLastNetworkPingTime(
                currentTime
            )
        }

        updateNetworkBitrate(
            currentTime
        )

        if (
            currentTime -
            lastKeepAliveTime() >=
            keepAliveInterval
        ) {

            sendKeepAlive()

            onKeepAliveSent()

            setLastKeepAliveTime(
                currentTime
            )
        }

        if (
            isConnected() &&
            currentTime -
            lastServerActivityTime() >=
            serverActivityTimeout
        ) {

            onServerTimeout()

            return false
        }

        return true
    }

    fun receivePacket(
        buffer: ByteArray,
        receivePacket: (
            DatagramPacket
        ) -> Unit,
        onPacketReceived: (
            Int
        ) -> Unit,
        onServerActivity: () -> Unit,
        onPeriodicReceiveLog: (
            Int
        ) -> Unit
    ): String? {

        val packet =
            DatagramPacket(
                buffer,
                buffer.size
            )

        packet.length =
            buffer.size

        receivePacket(
            packet
        )

        val length =
            packet.length

        if (
            length <= 0
        ) {

            return null
        }

        onPacketReceived(
            length
        )

        onServerActivity()

        if (
            length % 20 == 0
        ) {

            onPeriodicReceiveLog(
                length
            )
        }

        return String(
            packet.data,
            packet.offset,
            length,
            Charsets.UTF_8
        )
    }

    fun cleanupSocket(
        socket: DatagramSocket,
        getCurrentSocket: () ->
        DatagramSocket?,
        clearCurrentSocket: () -> Unit
    ) {

        try {

            socket.close()

        } catch (_: Exception) {
        }

        val currentSocket =
            getCurrentSocket()

        if (
            currentSocket ===
            socket
        ) {

            clearCurrentSocket()
        }
    }

    fun closeSocket(
        clearCurrentSocket: () -> Unit
    ) {

        try {

            udpManager.close()

        } catch (_: Throwable) {
        }

        clearCurrentSocket()
    }

    fun cleanupConnection(
        generation: Long,

        isGenerationCurrent: (
            Long
        ) -> Boolean,

        getCurrentChannel: () -> String,
        setReconnectChannel: (
            String
        ) -> Unit,

        clearTalkState: () -> Unit,
        stopRecording: () -> Unit,

        closeSocket: () -> Unit,

        clearServerAddress: () -> Unit,

        resetAudioReceiveState: () -> Unit,
        resetDecodeFailures: () -> Unit,

        clearPlaybackQueue: () -> Unit,
        requestPlaybackRecovery: () -> Unit,

        setCurrentChannel: (
            String
        ) -> Unit,
        setOnlineCount: (
            Int
        ) -> Unit,
        setCurrentChannelPrivate: (
            Boolean
        ) -> Unit,
        setCurrentChannelRequirePassword: (
            Boolean
        ) -> Unit,
        setChannelSwitching: (
            Boolean
        ) -> Unit,
        clearCachedChannelInfo: () -> Unit,
        clearUserList: () -> Unit,

        setTalkStatusReleased: () -> Unit,
        setConnected: (
            Boolean
        ) -> Unit,

        logger: (
            String
        ) -> Unit
    ) {

        if (
            !isGenerationCurrent(
                generation
            )
        ) {

            return
        }

        val currentChannel =
            getCurrentChannel()

        if (
            currentChannel.isNotBlank() &&
            currentChannel !=
            "public"
        ) {

            setReconnectChannel(
                currentChannel
            )
        }

        clearTalkState()

        stopRecording()

        closeSocket()

        clearServerAddress()

        resetAudioReceiveState()

        resetDecodeFailures()

        logger(
            "V23.1音频接收状态已重置，" +
                    "等待自动重连后的新音频流"
        )

        clearPlaybackQueue()

        requestPlaybackRecovery()

        if (
            currentChannel.isBlank() ||
            currentChannel ==
            "public"
        ) {

            setCurrentChannel(
                "public"
            )
        }

        setOnlineCount(
            0
        )

        setCurrentChannelPrivate(
            false
        )

        setCurrentChannelRequirePassword(
            false
        )

        setChannelSwitching(
            false
        )

        clearCachedChannelInfo()

        clearUserList()

        setTalkStatusReleased()

        setConnected(
            false
        )

        logger(
            "连接清理完成 generation=$generation"
        )
    }

    fun stopConnections(
        connectionLock: Any,
        getConnectionGeneration: () -> Long,
        setConnectionGeneration: (
            Long
        ) -> Unit,
        getNetworkJob: () -> Job?,
        setNetworkJob: (
            Job?
        ) -> Unit,
        closeSocket: () -> Unit
    ) {

        synchronized(
            connectionLock
        ) {

            val nextGeneration =
                getConnectionGeneration() + 1L

            setConnectionGeneration(
                nextGeneration
            )

            getNetworkJob()
                ?.cancel()

            setNetworkJob(
                null
            )
        }

        closeSocket()
    }

    suspend fun performReconnectHandshake(
        deviceId: String,
        walkieVersion: String,
        isShuttingDown: () -> Boolean,
        isGenerationCurrent: (
            Long
        ) -> Boolean,
        generation: Long,
        sendHello: (
            String
        ) -> Unit,
        resetNetworkStats: () -> Unit,
        setLastNetworkPingTime: (
            Long
        ) -> Unit,
        setLastKeepAliveTime: (
            Long
        ) -> Unit,
        setLastServerActivityTime: (
            Long
        ) -> Unit
    ) {

        /*
         * ========================================================
         * V24.9.1：
         *
         * HELLO连续发送3次。
         *
         * 三次之间40ms。
         *
         * 发送完成以后立即返回。
         *
         * 后面的connectOnce()马上进入
         * UDP receive循环等待WALKIE_CONNECTED。
         * ========================================================
         */

        repeat(
            3
        ) { index ->

            if (
                !isGenerationCurrent(
                    generation
                ) ||
                isShuttingDown()
            ) {

                return
            }

            sendHello(
                "$deviceId:$walkieVersion"
            )

            logger(
                "重连HELLO ${index + 1}/3 " +
                        "generation=$generation"
            )

            if (
                index < 2
            ) {

                delay(
                    40L
                )
            }
        }

        if (
            !isGenerationCurrent(
                generation
            ) ||
            isShuttingDown()
        ) {

            return
        }

        val now =
            System.currentTimeMillis()

        resetNetworkStats()

        setLastNetworkPingTime(
            now
        )

        setLastKeepAliveTime(
            now
        )

        setLastServerActivityTime(
            now
        )

        logger(
            "HELLO发送完成，进入UDP接收等待 " +
                    "generation=$generation"
        )
    }

    fun start() {

        logger(
            "WalkieConnectionManager 已启动"
        )
    }

    fun stop() {

        logger(
            "WalkieConnectionManager 已停止"
        )
    }
}