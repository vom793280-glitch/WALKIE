package com.example.walkie

import android.content.Intent
import com.example.walkie.audio.WalkieAudioPlayback
import kotlinx.coroutines.Job

class WalkieServiceLifecycleManager(
    private val connectionLock: Any,
    private val getConnectionGeneration: () -> Long,
    private val setConnectionGeneration: (Long) -> Unit,
    private val getNetworkJob: () -> Job?,
    private val setNetworkJob: (Job?) -> Unit,

    private val getIsConnected: () -> Boolean,
    private val getTalkAllowed: () -> Boolean,
    private val getTalkRequesting: () -> Boolean,

    private val setShuttingDown: (Boolean) -> Unit,

    private val sendMessageNow: (String) -> Unit,
    private val msgGoodbye: String,
    private val msgTalkStop: String,

    private val stopChannelRefreshWorker: () -> Unit,
    private val stopBackgroundDiagnostic: () -> Unit,
    private val stopRecording: () -> Unit,
    private val closeSocket: () -> Unit,

    private val audioPlayback: WalkieAudioPlayback,

    private val setTalkRequesting: (Boolean) -> Unit,
    private val setTalkAllowed: (Boolean) -> Unit,
    private val setSpeaking: (Boolean) -> Unit,
    private val setConnected: (Boolean) -> Unit,

    private val clearUserList: () -> Unit,
    private val clearServerUserIdentity: () -> Unit,

    private val setServerIp: (String?) -> Unit,
    private val setServerAddress: (java.net.InetAddress?) -> Unit,

    private val setCurrentChannel: (String) -> Unit,
    private val setCurrentChannelOnlineCount: (Int) -> Unit,
    private val setCurrentChannelPrivate: (Boolean) -> Unit,
    private val setCurrentChannelRequirePassword: (Boolean) -> Unit,

    private val setReconnectChannel: (String) -> Unit,
    private val setReconnectChannelPassword: (String) -> Unit,

    private val setChannelSwitching: (Boolean) -> Unit,

    private val setCachedChannelInfoList:
        (ArrayList<WalkieService.ChannelInfo>) -> Unit,

    private val setPendingCreateChannelName:
        (String) -> Unit,

    private val setPendingCreateChannelPassword:
        (String) -> Unit,

    private val resetAudioReceiveState: () -> Unit,
    private val resetAudioDecoderFailures: () -> Unit,

    private val setTalkStatusReleased: () -> Unit,
    private val broadcastMyUserInfo: () -> Unit,

    private val broadcastConnectionStatusStopped: () -> Unit,

    private val updateNotification: () -> Unit,

    private val logger: (String) -> Unit
) {

    fun stopAll() {

        setShuttingDown(
            true
        )

        try {

            if (
                getIsConnected()
            ) {

                if (
                    getTalkAllowed() ||
                    getTalkRequesting()
                ) {

                    sendMessageNow(
                        msgTalkStop
                    )
                }

                sendMessageNow(
                    msgGoodbye
                )
            }

        } catch (_: Exception) {
        }

        synchronized(
            connectionLock
        ) {

            setConnectionGeneration(
                getConnectionGeneration() + 1L
            )

            getNetworkJob()?.cancel()

            setNetworkJob(
                null
            )
        }

        stopChannelRefreshWorker()

        stopBackgroundDiagnostic()

        stopRecording()

        closeSocket()

        audioPlayback.release()

        setTalkRequesting(
            false
        )

        setTalkAllowed(
            false
        )

        setSpeaking(
            false
        )

        setConnected(
            false
        )

        clearUserList()

        clearServerUserIdentity()

        setServerIp(
            null
        )

        setServerAddress(
            null
        )

        setCurrentChannel(
            "public"
        )

        setCurrentChannelOnlineCount(
            0
        )

        setCurrentChannelPrivate(
            false
        )

        setCurrentChannelRequirePassword(
            false
        )

        setReconnectChannel(
            ""
        )

        setReconnectChannelPassword(
            ""
        )

        setChannelSwitching(
            false
        )

        setCachedChannelInfoList(
            ArrayList()
        )

        setPendingCreateChannelName(
            ""
        )

        setPendingCreateChannelPassword(
            ""
        )

        resetAudioReceiveState()

        resetAudioDecoderFailures()

        setTalkStatusReleased()

        broadcastMyUserInfo()

        broadcastConnectionStatusStopped()

        updateNotification()

        logger(
            "Service通信已完全停止"
        )
    }
}