package com.example.walkie

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import com.example.walkie.network.WalkieNetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WalkieNetworkMigration(
    private val context: Context,
    private val scopeLaunch: (
        suspend () -> Unit
    ) -> Job,
    private val logger: (String) -> Unit,

    private val getActiveNetwork: () -> Network?,
    private val setActiveNetwork: (Network?) -> Unit,

    private val getServerIp: () -> String?,
    private val getDeviceId: () -> String,

    private val isShuttingDown: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val setConnected: (Boolean) -> Unit,
    private val setNetworkAvailable: (Boolean) -> Unit,

    private val getNetworkMigrationJob: () -> Job?,
    private val setNetworkMigrationJob: (Job?) -> Unit,

    private val startConnection: (String) -> Unit,

    private val migrateUdp: suspend (
        String,
        Int,
        Network
    ) -> Unit,

    private val currentUdpSocket: () -> java.net.DatagramSocket?,

    private val setUdpSocket: (
        java.net.DatagramSocket?
    ) -> Unit,

    private val sendMessageNow: (String) -> Unit,

    private val sendNetworkPing: (Long) -> Unit,

    private val closeSocket: () -> Unit,

    private val clearUserList: () -> Unit,

    private val incrementConnectionGenerationAndCancelNetworkJob:
        () -> Unit,

    private val getConnectionLock: () -> Any,

    private val getServerPort: () -> Int,

    private val getHelloMessage: () -> String,

    private val getWalkieVersion: () -> String
) {

    private var networkMonitor:
            WalkieNetworkMonitor? =
        null
    fun registerNetworkCallback() {

        if (
            networkMonitor != null
        ) {

            return
        }

        networkMonitor =
            WalkieNetworkMonitor(

                context = context,

                onAvailable = { network ->

                    handleNetworkAvailable(
                        network
                    )
                },

                onLost = { network ->

                    handleNetworkLost(
                        network
                    )
                },

                onCapabilitiesChanged = {
                        network,
                        capabilities ->

                    handleNetworkCapabilitiesChanged(
                        network,
                        capabilities
                    )
                },

                logger = { message ->

                    logger(
                        message
                    )
                }
            )

        networkMonitor?.start()
    }

    fun unregisterNetworkCallback() {

        networkMonitor?.stop()

        networkMonitor =
            null

        setActiveNetwork(
            null
        )

        setNetworkAvailable(
            false
        )
    }

    fun handleNetworkAvailable(
        network: Network
    ) {

        val previousNetwork =
            getActiveNetwork()

        logger(
            "WALKIE ${getWalkieVersion()}: " +
                    "网络可用=$network"
        )

        /*
         * ========================================================
         * 网络没有发生变化
         * ========================================================
         */
        if (
            previousNetwork == null ||
            previousNetwork == network
        ) {

            setActiveNetwork(
                network
            )

            setNetworkAvailable(
                true
            )

            val ip =
                getServerIp()

            if (
                isShuttingDown() ||
                ip.isNullOrBlank()
            ) {

                return
            }

            getNetworkMigrationJob()
                ?.cancel()

            val newJob =
                scopeLaunch {

                    if (
                        isShuttingDown()
                    ) {

                        return@scopeLaunch
                    }

                    if (
                        getActiveNetwork() !=
                        network
                    ) {

                        return@scopeLaunch
                    }

                    if (
                        isConnected() &&
                        currentUdpSocket() != null &&
                        currentUdpSocket()?.isClosed == false
                    ) {

                        logger(
                            "WALKIE ${getWalkieVersion()}: " +
                                    "当前UDP仍健康，不创建重复Socket"
                        )

                        return@scopeLaunch
                    }

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "网络恢复，立即启动自动重连"
                    )

                    startConnection(
                        ip
                    )
                }

            setNetworkMigrationJob(
                newJob
            )

            return
        }

        /*
         * ========================================================
         * 检测到网络切换
         *
         * 无缝迁移原则：
         *
         * 1. 不关闭旧Socket
         * 2. 不清空用户列表
         * 3. 不修改 connected=false
         * 4. 不停止播放
         * 5. 新Socket建立成功后再切换
         * ========================================================
         */
        logger(
            "WALKIE ${getWalkieVersion()}: " +
                    "检测到网络切换 " +
                    "$previousNetwork -> $network，" +
                    "开始无缝迁移UDP"
        )

        setActiveNetwork(
            network
        )

        setNetworkAvailable(
            true
        )

        val ip =
            getServerIp()

        if (
            isShuttingDown() ||
            ip.isNullOrBlank()
        ) {

            return
        }

        val migrationJob =
            scopeLaunch {

                if (
                    isShuttingDown()
                ) {

                    return@scopeLaunch
                }

                if (
                    getActiveNetwork() !=
                    network
                ) {

                    return@scopeLaunch
                }

                try {

                    /*
                     * ====================================================
                     * 新Socket建立
                     * ====================================================
                     */
                    migrateUdp(
                        ip,
                        getServerPort(),
                        network
                    )

                    val newSocket =
                        currentUdpSocket()

                    if (
                        newSocket == null
                    ) {

                        logger(
                            "WALKIE ${getWalkieVersion()}: " +
                                    "UDP无缝迁移失败：新Socket为空"
                        )

                        return@scopeLaunch
                    }

                    synchronized(
                        getConnectionLock()
                    ) {

                        setUdpSocket(
                            newSocket
                        )
                    }

                    /*
                     * ====================================================
                     * 网络切换期间：
                     *
                     * connected保持原状态
                     * 不clearUserList()
                     * 不stop playback
                     * 不发送GOODBYE
                     * ====================================================
                     */

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "新UDP Socket接管成功，" +
                                "开始发送迁移HELLO"
                    )

                    /*
                     * ====================================================
                     * HELLO连续发送5次
                     *
                     * 与HarmonyOS迁移逻辑保持一致
                     * ====================================================
                     */
                    repeat(
                        5
                    ) { index ->

                        if (
                            isShuttingDown() ||
                            getActiveNetwork() !=
                            network
                        ) {

                            return@scopeLaunch
                        }

                        sendMessageNow(
                            "${getHelloMessage()}:${getDeviceId()}"
                        )

                        logger(
                            "WALKIE ${getWalkieVersion()}: " +
                                    "网络迁移HELLO " +
                                    "${index + 1}/5"
                        )

                        delay(
                            80L
                        )
                    }

                    /*
                     * ====================================================
                     * HELLO完成后发送PING
                     * ====================================================
                     */
                    sendNetworkPing(
                        System.currentTimeMillis()
                    )

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "网络迁移PING已发送"
                    )

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "UDP无缝迁移完成，" +
                                "旧Socket将在宽限期后关闭"
                    )

                } catch (
                    e: Throwable
                ) {

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "UDP无缝迁移失败=${e.message}"
                    )

                    /*
                     * 只有迁移失败才允许进入正常重连
                     */
                    if (
                        !isShuttingDown() &&
                        getActiveNetwork() ==
                        network
                    ) {

                        incrementConnectionGenerationAndCancelNetworkJob()

                        closeSocket()

                        setConnected(
                            false
                        )

                        startConnection(
                            ip
                        )
                    }

                } finally {

                    setNetworkMigrationJob(
                        null
                    )
                }
            }

        setNetworkMigrationJob(
            migrationJob
        )
    }

    fun testNetworkMigration() {

        val ip =
            getServerIp()

        val network =
            getActiveNetwork()

        if (
            isShuttingDown()
        ) {

            logger(
                "WALKIE ${getWalkieVersion()}: " +
                        "TEST_NETWORK_MIGRATION：当前正在关闭"
            )

            return
        }

        if (
            ip.isNullOrBlank()
        ) {

            logger(
                "WALKIE ${getWalkieVersion()}: " +
                        "TEST_NETWORK_MIGRATION：服务器IP为空"
            )

            return
        }

        if (
            network == null
        ) {

            logger(
                "WALKIE ${getWalkieVersion()}: " +
                        "TEST_NETWORK_MIGRATION：当前Network为空"
            )

            return
        }

        logger(
            "WALKIE ${getWalkieVersion()}: " +
                    "★★★★ 开始手动UDP迁移测试 ★★★★"
        )

        logger(
            "WALKIE ${getWalkieVersion()}: " +
                    "测试Network=$network " +
                    "server=$ip:${getServerPort()}"
        )

        getNetworkMigrationJob()
            ?.cancel()

        val testJob =
            scopeLaunch {

                try {

                    migrateUdp(
                        ip,
                        getServerPort(),
                        network
                    )

                    val newSocket =
                        currentUdpSocket()

                    if (
                        newSocket == null ||
                        newSocket.isClosed
                    ) {

                        logger(
                            "WALKIE ${getWalkieVersion()}: " +
                                    "TEST_NETWORK_MIGRATION：新Socket创建失败"
                        )

                        return@scopeLaunch
                    }

                    synchronized(
                        getConnectionLock()
                    ) {

                        setUdpSocket(
                            newSocket
                        )
                    }

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "TEST_NETWORK_MIGRATION：★★★★ 新Socket已接管 ★★★★ " +
                                "newPort=${newSocket.localPort}"
                    )

                    repeat(
                        5
                    ) { index ->

                        if (
                            isShuttingDown()
                        ) {

                            return@scopeLaunch
                        }

                        sendMessageNow(
                            "${getHelloMessage()}:${getDeviceId()}"
                        )

                        logger(
                            "WALKIE ${getWalkieVersion()}: " +
                                    "TEST_NETWORK_MIGRATION：HELLO " +
                                    "${index + 1}/5"
                        )

                        delay(
                            80L
                        )
                    }

                    sendNetworkPing(
                        System.currentTimeMillis()
                    )

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "TEST_NETWORK_MIGRATION：PING已发送"
                    )

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "★★★★ 手动UDP迁移测试完成 ★★★★"
                    )

                } catch (
                    e: Throwable
                ) {

                    logger(
                        "WALKIE ${getWalkieVersion()}: " +
                                "TEST_NETWORK_MIGRATION失败=${e.message}"
                    )

                } finally {

                    setNetworkMigrationJob(
                        null
                    )
                }
            }

        setNetworkMigrationJob(
            testJob
        )
    }

    fun handleNetworkLost(
        network: Network
    ) {

        if (
            getActiveNetwork() !=
            network
        ) {

            return
        }

        logger(
            "WALKIE ${getWalkieVersion()}: 当前网络丢失=$network"
        )

        setActiveNetwork(
            null
        )

        setNetworkAvailable(
            false
        )

        getNetworkMigrationJob()
            ?.cancel()

        setNetworkMigrationJob(
            null
        )

        setConnected(
            false
        )

        incrementConnectionGenerationAndCancelNetworkJob()

        closeSocket()

        clearUserList()

        logger(
            "WALKIE ${getWalkieVersion()}: 网络断开，旧连接已作废"
        )
    }

    fun handleNetworkCapabilitiesChanged(
        network: Network,
        capabilities: NetworkCapabilities
    ) {

        if (
            getActiveNetwork() ==
            network
        ) {

            logger(
                "WALKIE ${getWalkieVersion()}: " +
                        "网络能力变化=$capabilities"
            )
        }
    }
}