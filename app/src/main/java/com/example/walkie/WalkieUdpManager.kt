package com.example.walkie

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * ============================================================
 * WALKIE UDP Socket 管理器
 * ============================================================
 *
 * V24.9.2
 *
 * 本文件只负责 UDP Socket 本身：
 *
 * 1. 创建 Socket
 * 2. 绑定 Android Network
 * 3. 获取当前 Socket
 * 4. UDP 发送
 * 5. UDP 接收
 * 6. 网络切换无缝迁移
 * 7. 旧 Socket 延迟关闭
 *
 * 不处理：
 *
 * 1. HELLO
 * 2. LOGIN
 * 3. KEEPALIVE
 * 4. PING
 * 5. 频道
 * 6. 抢麦
 * 7. Opus
 *
 * 上面的业务仍然由 WalkieService 负责。
 *
 * ============================================================
 *
 * 无缝迁移原则：
 *
 *        旧 Socket
 *            │
 *            │ 不关闭
 *            ▼
 *        新 Socket
 *            │
 *            ▼
 *      绑定新的 Network
 *            │
 *            ▼
 *       新 Socket 接管
 *            │
 *            ├──── send() → 新 Socket
 *            │
 *            └──── receive() → 新 Socket
 *            │
 *            ▼
 *       等待 3 秒
 *            │
 *            ▼
 *        关闭旧 Socket
 *
 * ============================================================
 */
class WalkieUdpManager(
    private val scope: CoroutineScope,
    private val receiveTimeoutMs: Int = 500,
    private val oldSocketGraceMs: Long = 3000L,
    private val logger: (String) -> Unit = {}
) {

    /*
     * ========================================================
     * 线程锁
     * ========================================================
     */
    private val lock =
        Any()

    /*
     * ========================================================
     * 当前 Socket
     *
     * Service 所看到的“当前UDP连接”
     * 始终是这个 Socket。
     * ========================================================
     */
    private var activeSocket:
            DatagramSocket? =
        null

    /*
     * ========================================================
     * 迁移期间保留的旧 Socket
     * ========================================================
     */
    private var handoffOldSocket:
            DatagramSocket? =
        null

    /*
     * ========================================================
     * 旧 Socket 延迟关闭任务
     * ========================================================
     */
    private var handoffJob:
            Job? =
        null

    /*
     * ========================================================
     * 服务器信息
     * ========================================================
     */
    private var serverAddress:
            InetAddress? =
        null

    private var serverIp:
            String =
        ""

    private var serverPort:
            Int =
        0

    /*
     * ========================================================
     * 管理器状态
     * ========================================================
     */
    @Volatile
    private var closed: Boolean = false

    /*
     * ========================================================
     * 当前 Socket
     * ========================================================
     */
    fun currentSocket():
            DatagramSocket? {

        return synchronized(
            lock
        ) {

            activeSocket
        }
    }

    /*
     * ========================================================
     * 当前本地 UDP 端口
     * ========================================================
     */
    fun currentLocalPort():
            Int {

        return synchronized(
            lock
        ) {

            activeSocket
                ?.localPort
                ?: -1
        }
    }

    /*
     * ========================================================
     * 当前 Socket 是否可用
     * ========================================================
     */
    fun isOpen():
            Boolean {

        return synchronized(
            lock
        ) {

            !closed &&
                    activeSocket != null &&
                    activeSocket?.isClosed ==
                    false
        }
    }

    /*
     * ========================================================
     * 普通打开
     * ========================================================
     *
     * 用于：
     *
     * 1. 第一次连接
     * 2. 普通重连
     *
     * 这里可以关闭旧 Socket。
     *
     * 网络无缝迁移不要调用这个方法。
     */
    suspend fun open(
        ip: String,
        port: Int,
        network: Network?
    ) {

        if (
            closed
        ) {

            throw IllegalStateException(
                "WalkieUdpManager已经关闭"
            )
        }

        val cleanIp =
            ip.trim()

        if (
            cleanIp.isBlank()
        ) {

            throw IllegalArgumentException(
                "UDP服务器IP不能为空"
            )
        }

        val address =
            InetAddress.getByName(
                cleanIp
            )

        /*
         * 普通连接：
         *
         * 直接关闭当前旧 Socket。
         */
        closeAllSockets()

        val socket =
            createSocket(
                network
            )

        try {

            synchronized(
                lock
            ) {

                if (
                    closed
                ) {

                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }

                    throw IllegalStateException(
                        "WalkieUdpManager已经关闭"
                    )
                }

                serverIp =
                    cleanIp

                serverPort =
                    port

                serverAddress =
                    address

                activeSocket =
                    socket
            }

            logger(
                "WALKIE UDP MANAGER: " +
                        "普通Socket创建成功 " +
                        "server=$cleanIp:$port " +
                        "localPort=${socket.localPort}"
            )

        } catch (
            error: Throwable
        ) {

            try {
                socket.close()
            } catch (_: Exception) {
            }

            synchronized(
                lock
            ) {

                if (
                    activeSocket ===
                    socket
                ) {

                    activeSocket =
                        null
                }
            }

            throw error
        }
    }

    /*
     * ========================================================
     * 无缝网络迁移
     * ========================================================
     *
     * 核心：
     *
     * 旧 Socket 不关闭
     *        ↓
     * 新 Socket 创建
     *        ↓
     * 新 Socket 绑定新的 Network
     *        ↓
     * activeSocket 切到新 Socket
     *        ↓
     * 旧 Socket 保留
     *        ↓
     * 3秒后关闭旧 Socket
     *
     * 注意：
     *
     * 这个函数不负责 HELLO / PING / KEEPALIVE。
     * WalkieService 在迁移完成后自己发送。
     */
    suspend fun migrate(
        ip: String,
        port: Int,
        network: Network
    ) {

        if (
            closed
        ) {

            return
        }

        val cleanIp =
            ip.trim()

        if (
            cleanIp.isBlank()
        ) {

            return
        }

        val address =
            InetAddress.getByName(
                cleanIp
            )

        /*
         * ====================================================
         * 取得旧 Socket
         * ====================================================
         */
        val previousSocket =
            synchronized(
                lock
            ) {

                activeSocket
            }

        /*
         * 没有旧 Socket：
         *
         * 直接建立普通新连接。
         */
        if (
            previousSocket == null ||
            previousSocket.isClosed
        ) {

            logger(
                "WALKIE UDP MANAGER: " +
                        "没有有效旧Socket，执行普通打开"
            )

            open(
                cleanIp,
                port,
                network
            )

            return
        }

        /*
         * ====================================================
         * 如果上一次迁移还有遗留旧 Socket
         * 先清理。
         * ====================================================
         */
        finishPendingHandoff()

        /*
         * ====================================================
         * 创建新 Socket
         *
         * ★这里不会关闭旧 Socket★
         * ====================================================
         */
        val newSocket =
            createSocket(
                network
            )

        try {

            synchronized(
                lock
            ) {

                if (
                    closed
                ) {

                    try {
                        newSocket.close()
                    } catch (_: Exception) {
                    }

                    return
                }

                serverIp =
                    cleanIp

                serverPort =
                    port

                serverAddress =
                    address

                /*
                 * 保存旧 Socket。
                 */
                handoffOldSocket =
                    previousSocket

                /*
                 * ==================================================
                 * ★★★ 真正的切换点 ★★★
                 *
                 * 从这一行开始：
                 *
                 * currentSocket()
                 * send()
                 * receive()
                 *
                 * 全部使用新 Socket。
                 * ==================================================
                 */
                activeSocket =
                    newSocket
            }

            logger(
                "WALKIE UDP MANAGER: " +
                        "★★★★ 新Socket已经接管 ★★★★ " +
                        "oldPort=${previousSocket.localPort} " +
                        "newPort=${newSocket.localPort} " +
                        "network=$network"
            )

            /*
             * ==================================================
             * 旧 Socket 延迟关闭
             * ==================================================
             */
            scheduleOldSocketClose(
                previousSocket
            )

        } catch (
            error: Throwable
        ) {

            /*
             * 新 Socket 创建/切换失败。
             *
             * 绝对不要关闭旧 Socket。
             */
            try {
                newSocket.close()
            } catch (_: Exception) {
            }

            synchronized(
                lock
            ) {

                if (
                    activeSocket ===
                    newSocket
                ) {

                    activeSocket =
                        previousSocket
                }

                if (
                    handoffOldSocket ===
                    previousSocket
                ) {

                    handoffOldSocket =
                        null
                }
            }

            logger(
                "WALKIE UDP MANAGER: " +
                        "★★★★ 网络迁移失败，旧Socket继续保留 ★★★★ " +
                        "error=${error.message}"
            )

            throw error
        }
    }

    /*
     * ========================================================
     * 创建 UDP Socket
     * ========================================================
     */
    private fun createSocket(
        network: Network?
    ):
            DatagramSocket {

        val socket =
            DatagramSocket()

        try {

            socket.soTimeout =
                receiveTimeoutMs

        } catch (
            error: Throwable
        ) {

            try {
                socket.close()
            } catch (_: Exception) {
            }

            throw error
        }

        /*
         * 接收缓冲。
         */
        try {

            socket.receiveBufferSize =
                128 * 1024

        } catch (_: Exception) {
        }

        /*
         * 发送缓冲。
         */
        try {

            socket.sendBufferSize =
                64 * 1024

        } catch (_: Exception) {
        }

        /*
         * Android 6.0+：
         *
         * 把 Socket 固定到指定 Network。
         */
        if (
            network != null
        ) {

            try {

                network.bindSocket(
                    socket
                )

                logger(
                    "WALKIE UDP MANAGER: " +
                            "Socket绑定Network成功 " +
                            "network=$network " +
                            "localPort=${socket.localPort}"
                )

            } catch (
                error: Throwable
            ) {

                try {
                    socket.close()
                } catch (_: Exception) {
                }

                throw error
            }
        }

        return socket
    }

    /*
     * ========================================================
     * UDP发送
     * ========================================================
     *
     * 永远使用当前 activeSocket。
     *
     * 因此网络切换以后：
     *
     * activeSocket = 新 Socket
     *
     * send() 自动发送到新 Socket。
     */
    fun send(
        data: ByteArray
    ) {

        val socket:
                DatagramSocket?

        val address:
                InetAddress?

        val port:
                Int

        synchronized(
            lock
        ) {

            socket =
                activeSocket

            address =
                serverAddress

            port =
                serverPort
        }

        if (
            closed ||
            socket == null ||
            socket.isClosed ||
            address == null ||
            port <= 0
        ) {

            return
        }

        val packet =
            DatagramPacket(
                data,
                data.size,
                address,
                port
            )

        try {

            socket.send(
                packet
            )

        } catch (
            error: Throwable
        ) {

            /*
             * 只有当发送失败的 Socket
             * 仍然是当前 activeSocket 时，
             * 才通知上层。
             *
             * 如果它已经是旧 Socket：
             *
             * 忽略。
             */
            val isCurrent =
                synchronized(
                    lock
                ) {

                    activeSocket ===
                            socket
                }

            if (
                isCurrent &&
                !closed
            ) {

                logger(
                    "WALKIE UDP MANAGER: " +
                            "当前Socket发送失败=" +
                            error.message
                )
            } else {

                logger(
                    "WALKIE UDP MANAGER: " +
                            "旧Socket发送失败，忽略"
                )
            }
        }
    }

    /*
     * ========================================================
     * 文本发送
     * ========================================================
     */
    fun send(
        text: String
    ) {

        send(
            text.toByteArray(
                Charsets.UTF_8
            )
        )
    }

    /*
     * ========================================================
     * UDP接收
     * ========================================================
     *
     * 由 WalkieService 原来的 receive 循环调用。
     *
     * 这里每次都重新取得 activeSocket。
     *
     * 因此：
     *
     * Wi-Fi Socket A
     *        ↓
     * 网络迁移
     *        ↓
     * activeSocket = B
     *        ↓
     * 下一次 receive()
     *        ↓
     * 自动从 B 接收。
     */
    fun receive(
        packet: DatagramPacket
    ) {

        val socket =
            synchronized(
                lock
            ) {

                activeSocket
            }

        if (
            closed ||
            socket == null ||
            socket.isClosed
        ) {

            throw SocketException(
                "UDP Socket 未打开"
            )
        }

        socket.receive(
            packet
        )
    }

    /*
     * ========================================================
     * 判断 Socket 是否还是当前 Socket
     * ========================================================
     */
    fun isCurrentSocket(
        socket: DatagramSocket
    ):
            Boolean {

        return synchronized(
            lock
        ) {

            activeSocket ===
                    socket
        }
    }

    /*
     * ========================================================
     * 延迟关闭旧 Socket
     * ========================================================
     */
    private fun scheduleOldSocketClose(
        socket: DatagramSocket
    ) {

        handoffJob?.cancel()

        handoffJob =
            scope.launch {

                delay(
                    oldSocketGraceMs
                )

                val socketToClose =
                    synchronized(lock) {

                        if (
                            handoffOldSocket ===
                            socket
                        ) {

                            handoffOldSocket =
                                null

                            socket

                        } else {

                            null
                        }
                    }

                if (
                    socketToClose != null
                ) {

                    /*
                     * 先记录端口。
                     *
                     * 因为Socket close()以后，
                     * localPort可能返回-1。
                     */
                    val oldPort =
                        socketToClose.localPort

                    try {

                        socketToClose.close()

                    } catch (_: Exception) {
                    }

                    logger(
                        "WALKIE UDP MANAGER: " +
                                "★★★★ 旧Socket延迟关闭 ★★★★ " +
                                "port=$oldPort"
                    )
                }
            }
    }

    /*
     * ========================================================
     * 清理上一次迁移留下的旧 Socket
     * ========================================================
     */
    private fun finishPendingHandoff() {

        handoffJob?.cancel()

        handoffJob =
            null

        val socket =
            synchronized(
                lock
            ) {

                val old =
                    handoffOldSocket

                handoffOldSocket =
                    null

                old
            }

        if (
            socket != null
        ) {

            try {

                socket.close()

            } catch (_: Exception) {
            }

            logger(
                "WALKIE UDP MANAGER: " +
                        "清理上一次handoff旧Socket " +
                        "port=${socket.localPort}"
            )
        }
    }

    /*
     * ========================================================
     * 关闭全部 Socket
     * ========================================================
     *
     * 只用于：
     *
     * 1. 正常重连
     * 2. Service停止
     * 3. Service销毁
     *
     * 网络迁移绝对不能调用。
     */
    private fun closeAllSockets() {

        handoffJob?.cancel()

        handoffJob =
            null

        val sockets =
            ArrayList<DatagramSocket>()

        synchronized(
            lock
        ) {

            val current =
                activeSocket

            val old =
                handoffOldSocket

            activeSocket =
                null

            handoffOldSocket =
                null

            if (
                current != null
            ) {

                sockets.add(
                    current
                )
            }

            if (
                old != null &&
                old !== current
            ) {

                sockets.add(
                    old
                )
            }
        }

        for (
        socket in sockets
        ) {

            try {

                socket.close()

            } catch (_: Exception) {
            }
        }
    }

    /*
     * ========================================================
     * 完全关闭 Manager
     * ========================================================
     */
    fun close() {

        closeAllSockets()

        synchronized(lock) {
            activeSocket = null
            handoffOldSocket = null
            serverAddress = null
            serverIp = ""
            serverPort = 0
        }

        logger(
            "WALKIE UDP MANAGER: " +
                    "UDP Manager已关闭当前Socket，可重新open"
        )
    }
}