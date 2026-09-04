package com.example.walkie

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WalkieBackgroundDiagnostics(
    private val scope: CoroutineScope,
    private val isShuttingDown: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val getUdpKeepAliveCount: () -> Long,
    private val getUdpReceiveCount: () -> Long,
    private val getQueueSize: () -> Int,
    private val getUserCount: () -> Int,
    private val getSocketPort: () -> Int,
    private val getConnectionGeneration: () -> Long,
    private val getDeviceLogId: () -> String,
    private val logger: (String) -> Unit
) {

    private var diagnosticJob: Job? = null

    private var heartbeatCount = 0L

    fun start() {

        if (
            diagnosticJob?.isActive ==
            true
        ) {

            return
        }

        diagnosticJob =
            scope.launch {

                while (
                    isActive &&
                    !isShuttingDown()
                ) {

                    delay(
                        5000L
                    )

                    heartbeatCount++

                    logger(
                        "WALKIE BG: alive " +
                                "count=$heartbeatCount " +
                                "connected=${isConnected()} " +
                                "keepalive=${getUdpKeepAliveCount()} " +
                                "rx=${getUdpReceiveCount()} " +
                                "queue=${getQueueSize()} " +
                                "users=${getUserCount()} " +
                                "socketPort=${getSocketPort()} " +
                                "generation=${getConnectionGeneration()} " +
                                "device=${getDeviceLogId()}"
                    )
                }
            }
    }

    fun stop() {

        diagnosticJob?.cancel()

        diagnosticJob =
            null
    }
}