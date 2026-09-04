package com.example.walkie

import java.net.DatagramSocket

class WalkieNetworkMaintenanceManager(
    private val walkieConnectionManager:
    WalkieConnectionManager,

    private val networkPingInterval: Long,
    private val keepAliveInterval: Long,
    private val serverActivityTimeout: Long,

    private val isConnected: () -> Boolean,

    private val getLastNetworkPingTime: () -> Long,
    private val setLastNetworkPingTime: (Long) -> Unit,

    private val sendNetworkPing: (Long) -> Unit,
    private val expireNetworkPings: (Long) -> Unit,
    private val updateNetworkBitrate: (Long) -> Unit,

    private val getLastKeepAliveTime: () -> Long,
    private val setLastKeepAliveTime: (Long) -> Unit,

    private val sendKeepAlive: () -> Unit,
    private val onKeepAliveSent: (DatagramSocket) -> Unit,

    private val getLastServerActivityTime: () -> Long,

    private val logger: (String) -> Unit
) {

    fun perform(
        currentTime: Long,
        socket: DatagramSocket,
        generation: Long
    ): Boolean {

        val maintenanceOk =
            walkieConnectionManager
                .performNetworkMaintenance(

                    currentTime =
                        currentTime,

                    isConnected = {

                        isConnected()
                    },

                    lastNetworkPingTime = {

                        getLastNetworkPingTime()
                    },

                    setLastNetworkPingTime = {
                            value ->

                        setLastNetworkPingTime(
                            value
                        )
                    },

                    networkPingInterval =
                        networkPingInterval,

                    sendNetworkPing = {
                            value ->

                        sendNetworkPing(
                            value
                        )
                    },

                    expireNetworkPings = {
                            value ->

                        expireNetworkPings(
                            value
                        )
                    },

                    updateNetworkBitrate = {
                            value ->

                        updateNetworkBitrate(
                            value
                        )
                    },

                    lastKeepAliveTime = {

                        getLastKeepAliveTime()
                    },

                    setLastKeepAliveTime = {
                            value ->

                        setLastKeepAliveTime(
                            value
                        )
                    },

                    keepAliveInterval =
                        keepAliveInterval,

                    sendKeepAlive = {

                        sendKeepAlive()
                    },

                    onKeepAliveSent = {

                        onKeepAliveSent(
                            socket
                        )
                    },

                    lastServerActivityTime = {

                        getLastServerActivityTime()
                    },

                    serverActivityTimeout =
                        serverActivityTimeout,

                    onServerTimeout = {

                        logger(
                            "WALKIE UDP: " +
                                    "server timeout " +
                                    "port=${socket.localPort}"
                        )
                    }
                )

        return maintenanceOk
    }

    fun resetPingTime() {

        setLastNetworkPingTime(
            0L
        )
    }

    fun resetKeepAliveTime() {

        setLastKeepAliveTime(
            0L
        )
    }
}