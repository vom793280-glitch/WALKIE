package com.example.walkie.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * WALKIE 网络监听模块。
 *
 * 只负责 ConnectivityManager / NetworkCallback。
 * UDP Socket、重连、频道恢复等业务逻辑仍由 WalkieService 负责。
 */
class WalkieNetworkMonitor(
    context: Context,
    private val onAvailable: (Network) -> Unit,
    private val onLost: (Network) -> Unit,
    private val onCapabilitiesChanged: (
        Network,
        NetworkCapabilities
    ) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = {}
) {

    private val connectivityManager =
        context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

    private val lock = Any()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {

        synchronized(lock) {

            // 已经启动监听，直接返回，避免重复注册
            if (callback != null) {
                return
            }

            val networkCallback =
                object : ConnectivityManager.NetworkCallback() {

                    override fun onAvailable(
                        network: Network
                    ) {
                        // 明确调用外部传入的回调，
                        // 避免与 NetworkCallback.onAvailable() 自身递归
                        this@WalkieNetworkMonitor.onAvailable(network)
                    }

                    override fun onLost(
                        network: Network
                    ) {
                        // 明确调用外部传入的回调
                        this@WalkieNetworkMonitor.onLost(network)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        // 明确调用外部传入的回调
                        this@WalkieNetworkMonitor.onCapabilitiesChanged(
                            network,
                            networkCapabilities
                        )
                    }
                }

            try {

                connectivityManager.registerDefaultNetworkCallback(
                    networkCallback
                )

                callback = networkCallback

                logger(
                    "WALKIE: 网络监听注册成功"
                )

            } catch (e: Exception) {

                callback = null

                logger(
                    "WALKIE: 网络监听失败=${e.message}"
                )
            }
        }
    }

    fun stop() {

        synchronized(lock) {

            val current = callback
                ?: return

            try {

                connectivityManager.unregisterNetworkCallback(
                    current
                )

            } catch (_: Exception) {
                // 忽略注销过程中可能出现的异常
            }

            callback = null

            logger(
                "WALKIE: 网络监听已注销"
            )
        }
    }
}