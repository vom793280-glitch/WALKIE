package com.example.walkie

import com.example.walkie.audio.WalkieAudioPlayer
import com.example.walkie.audio.WalkieAudioPlayback
import com.example.walkie.network.WalkieNetworkMonitor
import com.example.walkie.network.WalkieNetworkStats
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID

class WalkieService : Service() {

    companion object {

        private const val WALKIE_VERSION = "V24.9.1"

        const val ACTION_START =
            "com.example.walkie.ACTION_START"

        const val ACTION_STOP =
            "com.example.walkie.ACTION_STOP"

        const val ACTION_SPEAK_START =
            "com.example.walkie.ACTION_SPEAK_START"

        const val ACTION_SPEAK_STOP =
            "com.example.walkie.ACTION_SPEAK_STOP"

        const val ACTION_SET_NICKNAME =
            "com.example.walkie.ACTION_SET_NICKNAME"

        const val ACTION_CHANNEL_LIST =
            "com.example.walkie.ACTION_CHANNEL_LIST"

        const val ACTION_JOIN_CHANNEL =
            "com.example.walkie.ACTION_JOIN_CHANNEL"

        const val ACTION_CREATE_CHANNEL =
            "com.example.walkie.ACTION_CREATE_CHANNEL"

        const val ACTION_DELETE_CHANNEL =
            "com.example.walkie.ACTION_DELETE_CHANNEL"

        const val ACTION_CONNECTION_STATUS =
            "com.example.walkie.ACTION_CONNECTION_STATUS"

        const val ACTION_TALK_STATUS =
            "com.example.walkie.ACTION_TALK_STATUS"

        const val ACTION_CHANNEL_STATUS =
            "com.example.walkie.ACTION_CHANNEL_STATUS"

        const val ACTION_CHANNEL_DELETED =
            "com.example.walkie.ACTION_CHANNEL_DELETED"

        const val ACTION_MY_USER_INFO =
            "com.example.walkie.ACTION_MY_USER_INFO"

        const val ACTION_USER_LIST =
            "com.example.walkie.ACTION_USER_LIST"

        const val ACTION_NETWORK_STATUS =
            "com.example.walkie.ACTION_NETWORK_STATUS"

        const val EXTRA_SERVER_IP =
            "com.example.walkie.EXTRA_SERVER_IP"

        const val EXTRA_DEVICE_ID =
            "com.example.walkie.EXTRA_DEVICE_ID"

        const val EXTRA_CONNECTED =
            "com.example.walkie.EXTRA_CONNECTED"

        const val EXTRA_TALK_STATUS =
            "com.example.walkie.EXTRA_TALK_STATUS"

        const val EXTRA_CHANNEL_LIST =
            "com.example.walkie.EXTRA_CHANNEL_LIST"

        const val EXTRA_CHANNEL_INFO =
            "com.example.walkie.EXTRA_CHANNEL_INFO"

        const val EXTRA_CURRENT_CHANNEL =
            "com.example.walkie.EXTRA_CURRENT_CHANNEL"

        const val EXTRA_CHANNEL_MESSAGE =
            "com.example.walkie.EXTRA_CHANNEL_MESSAGE"

        const val EXTRA_CHANNEL_NAME =
            "com.example.walkie.EXTRA_CHANNEL_NAME"

        const val EXTRA_CHANNEL_PASSWORD =
            "com.example.walkie.EXTRA_CHANNEL_PASSWORD"

        const val EXTRA_CHANNEL_PRIVATE =
            "com.example.walkie.EXTRA_CHANNEL_PRIVATE"

        const val EXTRA_CHANNEL_REQUIRE_PASSWORD =
            "com.example.walkie.EXTRA_CHANNEL_REQUIRE_PASSWORD"

        const val EXTRA_CHANNEL_ONLINE_COUNT =
            "com.example.walkie.EXTRA_CHANNEL_ONLINE_COUNT"

        const val EXTRA_DELETED_CHANNEL =
            "com.example.walkie.EXTRA_DELETED_CHANNEL"

        const val EXTRA_MY_USER_ID =
            "com.example.walkie.EXTRA_MY_USER_ID"

        const val EXTRA_MY_USERNAME =
            "com.example.walkie.EXTRA_MY_USERNAME"

        const val EXTRA_USER_LIST =
            "com.example.walkie.EXTRA_USER_LIST"

        const val EXTRA_NETWORK_LATENCY =
            "com.example.walkie.EXTRA_NETWORK_LATENCY"

        const val EXTRA_NETWORK_LOSS =
            "com.example.walkie.EXTRA_NETWORK_LOSS"

        const val EXTRA_NETWORK_QUALITY =
            "com.example.walkie.EXTRA_NETWORK_QUALITY"

        const val EXTRA_NETWORK_BITRATE =
            "com.example.walkie.EXTRA_NETWORK_BITRATE"

        const val EXTRA_NETWORK_UPLOAD_BITRATE =
            "com.example.walkie.EXTRA_NETWORK_UPLOAD_BITRATE"

        const val EXTRA_NETWORK_DOWNLOAD_BITRATE =
            "com.example.walkie.EXTRA_NETWORK_DOWNLOAD_BITRATE"

        const val EXTRA_NETWORK_JITTER =
            "com.example.walkie.EXTRA_NETWORK_JITTER"

        const val TALK_STATUS_NONE =
            "NONE"

        const val TALK_STATUS_REQUESTING =
            "REQUESTING"

        const val TALK_STATUS_ALLOWED =
            "ALLOWED"

        const val TALK_STATUS_BUSY =
            "BUSY"

        const val TALK_STATUS_RELEASED =
            "RELEASED"

        private const val CHANNEL_ID =
            "walkie_service"

        private const val NOTIFICATION_ID =
            1001

        private const val SERVER_PORT =
            50000

        private const val SAMPLE_RATE =
            16000

        private const val AUDIO_PACKET_SIZE =
            640

        private const val KEEP_ALIVE_INTERVAL =
            5000L

        private const val SOCKET_RECEIVE_TIMEOUT =
            100

        private const val SERVER_ACTIVITY_TIMEOUT =
            120000L

        private const val INITIAL_RECONNECT_INTERVAL =
            100L

        private const val MAX_RECONNECT_INTERVAL =
            500L

        private const val NETWORK_PING_INTERVAL =
            2000L

        private const val NETWORK_PING_TIMEOUT =
            1800L

        private const val NETWORK_PING_WINDOW =
            20

        private const val NETWORK_STATUS_MIN_INTERVAL =
            500L

        private const val NETWORK_BITRATE_WINDOW =
            5000L

        private const val PLAYBACK_QUEUE_CAPACITY =
            40

        private const val PLAYBACK_START_BUFFER_PACKETS =
            3

        private const val PLAYBACK_RECOVERY_BUFFER_PACKETS =
            3

        private const val PLAYBACK_MAX_QUEUE_PACKETS =
            24

        private const val PLAYBACK_GAIN =
            1.0f

        private const val AUDIO_V231_MAX_PACKET_SIZE =
            1220

        private const val MAX_OPUS_PACKET_SIZE =
            1208

        private const val MAX_DECODED_PCM_SAMPLES =
            4096

        private const val DEVICE_PREFS_NAME =
            "walkie_device_identity"

        private const val DEVICE_ID_KEY =
            "device_id"

        private const val PROFILE_PREFS_NAME =
            "walkie_profile_v20"

        private const val NICKNAME_KEY =
            "nickname"

        private const val MSG_HELLO =
            "WALKIE_HELLO"

        private const val MSG_CONNECTED =
            "WALKIE_CONNECTED"

        private const val MSG_KEEP_ALIVE =
            "WALKIEKEEPALIVE"

        private const val MSG_GOODBYE =
            "WALKIE_GOODBYE"

        private const val MSG_TALK_START =
            "WALKIE_TALK_START"

        private const val MSG_TALK_STOP =
            "WALKIE_TALK_STOP"

        private const val MSG_TALK_OK =
            "WALKIE_TALK_OK"

        private const val MSG_TALK_BUSY =
            "WALKIE_TALK_BUSY"

        private const val MSG_TALK_RELEASED =
            "WALKIE_TALK_RELEASED"

        private const val MSG_CHANNEL_LIST =
            "WALKIE_CHANNEL_LIST"

        private const val MSG_CHANNEL_JOINED =
            "WALKIE_CHANNEL_JOINED"

        private const val MSG_CHANNEL_LEFT =
            "WALKIE_CHANNEL_LEFT"

        private const val MSG_CHANNEL_CREATED =
            "WALKIE_CHANNEL_CREATED"

        private const val MSG_CHANNEL_DELETED =
            "WALKIE_CHANNEL_DELETED"

        private const val MSG_CHANNEL_ERROR =
            "WALKIE_CHANNEL_ERROR"

        private const val MSG_LOGIN =
            "WALKIE_LOGIN"

        private const val MSG_USER_OK =
            "WALKIE_USER_OK"

        private const val MSG_USER_STATUS =
            "WALKIE_USER_STATUS"

        private const val MSG_CHANNEL_MEMBERS =
            "WALKIE_CHANNEL_MEMBERS"

        private const val MSG_TALKING =
            "WALKIE_TALKING"

        private const val MSG_NET_PING =
            "WALKIE_NET_PING"

        private const val MSG_NET_PONG =
            "WALKIE_NET_PONG"
    }

    data class ChannelInfo(
        val name: String,
        val onlineCount: Int,
        val isPrivate: Boolean
    )

    data class UserInfo(
        val userId: String,
        val username: String
    )

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    private val udpManager by lazy {

        WalkieUdpManager(
            scope = serviceScope,
            receiveTimeoutMs = SOCKET_RECEIVE_TIMEOUT,
            oldSocketGraceMs = 3000L,
            logger = { message ->
                println(message)
            }
        )
    }

    private val sessionState =
        WalkieSessionState()

    private val walkieIdentityManager by lazy {

        WalkieIdentityManager(
            context = applicationContext,
            scope = serviceScope,
            devicePrefsName = DEVICE_PREFS_NAME,
            deviceIdKey = DEVICE_ID_KEY,
            profilePrefsName = PROFILE_PREFS_NAME,
            nicknameKey = NICKNAME_KEY,
            actionMyUserInfo = ACTION_MY_USER_INFO,
            actionUserList = ACTION_USER_LIST,
            extraMyUserId = EXTRA_MY_USER_ID,
            extraMyUsername = EXTRA_MY_USERNAME,
            extraUserList = EXTRA_USER_LIST,
            isConnected = {
                isConnected
            },
            isShuttingDown = {
                shuttingDown
            },
            sendMessageNow = { message ->
                sendMessageNow(message)
            },
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieServiceRuntimeManager by lazy {

        WalkieServiceRuntimeManager(
            service = this,
            context = applicationContext,
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            notificationTitle = "WALKIE V20.2",
            notificationTextProvider = {

                if (isConnected) {
                    "频道：$currentChannel  👥 ${currentChannelOnlineCount}人"
                } else {
                    "正在连接服务器"
                }
            },
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieAudioRouteManager by lazy {

        WalkieAudioRouteManager(
            context = applicationContext,
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieBackgroundDiagnostics by lazy {

        WalkieBackgroundDiagnostics(
            scope = serviceScope,
            isShuttingDown = {
                shuttingDown
            },
            isConnected = {
                isConnected
            },
            getUdpKeepAliveCount = {
                udpKeepAliveCount
            },
            getUdpReceiveCount = {
                udpReceiveCount
            },
            getQueueSize = {
                audioPlayback.queueSize()
            },
            getUserCount = {
                currentUserList.size
            },
            getSocketPort = {
                udpSocket?.localPort ?: -1
            },
            getConnectionGeneration = {
                connectionGeneration
            },
            getDeviceLogId = {
                deviceLogId()
            },
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieNetworkMaintenanceManager by lazy {

        WalkieNetworkMaintenanceManager(

            walkieConnectionManager =
                walkieConnectionManager,

            networkPingInterval =
                NETWORK_PING_INTERVAL,

            keepAliveInterval =
                KEEP_ALIVE_INTERVAL,

            serverActivityTimeout =
                SERVER_ACTIVITY_TIMEOUT,

            isConnected = {
                isConnected
            },

            getLastNetworkPingTime = {
                lastNetworkPingTime
            },

            setLastNetworkPingTime = {
                    value ->

                lastNetworkPingTime =
                    value
            },

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

            getLastKeepAliveTime = {
                lastKeepAliveTime
            },

            setLastKeepAliveTime = {
                    value ->

                lastKeepAliveTime =
                    value
            },

            sendKeepAlive = {

                sendMessageNow(
                    "$MSG_KEEP_ALIVE:$deviceId"
                )
            },

            onKeepAliveSent = {
                    socket ->

                udpKeepAliveCount++

                println(
                    "WALKIE UDP: " +
                            "keepalive count=$udpKeepAliveCount " +
                            "port=${socket.localPort} " +
                            "generation=$connectionGeneration"
                )
            },

            getLastServerActivityTime = {
                lastServerActivityTime
            },

            logger = { message ->

                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private var deviceId: String
        get() =
            walkieIdentityManager.deviceId
        set(value) {
            walkieIdentityManager.setDeviceId(value)
        }

    private val nickname: String
        get() =
            walkieIdentityManager.nickname

    private val myUserId: String
        get() =
            walkieIdentityManager.myUserId

    private val myUsername: String
        get() =
            walkieIdentityManager.myUsername

    private val currentUserList:
            ArrayList<UserInfo>
        get() =
            walkieIdentityManager.currentUserList

    private var udpSocket:
            DatagramSocket? =
        null

    private var serverAddress:
            InetAddress? =
        null

    private var serverIp:
            String? =
        null

    private var isConnected =
        false

    private var isNetworkAvailable =
        true

    private var lastNetworkPingTime =
        0L

    private var activeNetwork:
            Network? =
        null

    private var networkMigrationJob:
            Job? =
        null

    private val connectionLock: Any
        get() =
            walkieConnectionManager
                .connectionLock

    private var connectionGeneration: Long
        get() =
            walkieConnectionManager
                .connectionGeneration
        set(value) {
            walkieConnectionManager
                .connectionGeneration =
                value
        }

    private var networkMonitor:
            WalkieNetworkMonitor? =
        null

    private var networkJob: Job?
        get() =
            walkieConnectionManager
                .networkJob
        set(value) {
            walkieConnectionManager
                .networkJob =
                value
        }

    private var udpKeepAliveCount =
        0L

    private var udpReceiveCount =
        0L

    private val networkStats by lazy {

        WalkieNetworkStats(
            serverPort = SERVER_PORT,
            pingMessagePrefix = MSG_NET_PING,
            pongMessagePrefix = MSG_NET_PONG,
            pingWindowSize = NETWORK_PING_WINDOW,
            pingTimeoutMs = NETWORK_PING_TIMEOUT,
            bitrateWindowMs = NETWORK_BITRATE_WINDOW,
            statusMinIntervalMs = NETWORK_STATUS_MIN_INTERVAL,
            defaultRecoveryPackets =
                PLAYBACK_RECOVERY_BUFFER_PACKETS,
            socketProvider = {
                udpSocket
            },
            serverAddressProvider = {
                serverAddress
            },
            isConnectedProvider = {
                isConnected
            },
            packageNameProvider = {
                packageName
            },
            context = applicationContext,
            actionNetworkStatus = ACTION_NETWORK_STATUS,
            extraLatency = EXTRA_NETWORK_LATENCY,
            extraLoss = EXTRA_NETWORK_LOSS,
            extraQuality = EXTRA_NETWORK_QUALITY,
            extraBitrate = EXTRA_NETWORK_BITRATE,
            extraUploadBitrate =
                EXTRA_NETWORK_UPLOAD_BITRATE,
            extraDownloadBitrate =
                EXTRA_NETWORK_DOWNLOAD_BITRATE,
            extraJitter = EXTRA_NETWORK_JITTER,
            logger = {
                    message ->
                println(message)
            }
        )
    }

    private val networkLatencyMs: Long
        get() =
            networkStats.latencyMs

    private val networkJitterMs: Long
        get() =
            networkStats.jitterMs

    private val networkLossPercent: Float
        get() =
            networkStats.lossPercent

    private val adaptivePlaybackRecoveryPackets: Int
        get() =
            networkStats.adaptiveRecoveryPackets

    private val audioPlayer by lazy {

        WalkieAudioPlayer(
            context = applicationContext,
            sampleRate = SAMPLE_RATE,
            packetSize = AUDIO_PACKET_SIZE,
            gain = PLAYBACK_GAIN
        ) { message ->
            println(message)
        }
    }

    private val audioPlayback by lazy {

        WalkieAudioPlayback(
            audioPlayer = audioPlayer,
            queueCapacity = PLAYBACK_QUEUE_CAPACITY,
            startBufferPackets =
                PLAYBACK_START_BUFFER_PACKETS,
            recoveryBufferPackets =
                PLAYBACK_RECOVERY_BUFFER_PACKETS,
            maxQueuePackets =
                PLAYBACK_MAX_QUEUE_PACKETS,
            latencyProvider = {
                networkLatencyMs
            },
            lossProvider = {
                networkLossPercent
            },
            jitterProvider = {
                networkJitterMs
            },
            recoveryPacketsProvider = {
                adaptivePlaybackRecoveryPackets
            },
            logger = { message ->
                println(message)
            }
        )
    }

    private val audioProtocol by lazy {

        WalkieAudioProtocol(
            streamId =
                UUID.randomUUID()
                    .leastSignificantBits
                    .and(
                        0xFFFF_FFFFL
                    ),
            logger = { message ->
                println(message)
            }
        )
    }

    private val walkieAudioReceiver by lazy {

        WalkieAudioReceiver(
            sampleRate = SAMPLE_RATE,
            audioMaxPacketSize =
                AUDIO_V231_MAX_PACKET_SIZE,
            maxOpusPacketSize =
                MAX_OPUS_PACKET_SIZE,
            maxDecodedPcmSamples =
                MAX_DECODED_PCM_SAMPLES,
            audioProtocol =
                audioProtocol,
            opusDecoderProvider = {
                opusDecoder
            },
            audioPlayback =
                audioPlayback,
            recordAudioReceive = { bytes ->
                recordAudioReceive(bytes)
            },
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieMessageDispatcher by lazy {

        WalkieMessageDispatcher(

            msgConnected =
                MSG_CONNECTED,

            msgKeepAlive =
                MSG_KEEP_ALIVE,

            msgUserOk =
                MSG_USER_OK,

            msgUserStatus =
                MSG_USER_STATUS,

            msgChannelMembers =
                MSG_CHANNEL_MEMBERS,

            msgChannelList =
                MSG_CHANNEL_LIST,

            msgChannelJoined =
                MSG_CHANNEL_JOINED,

            msgChannelCreated =
                MSG_CHANNEL_CREATED,

            msgChannelDeleted =
                MSG_CHANNEL_DELETED,

            msgChannelError =
                MSG_CHANNEL_ERROR,

            msgChannelLeft =
                MSG_CHANNEL_LEFT,

            msgNetPong =
                MSG_NET_PONG,

            isConnectionGenerationCurrent = {
                    generation ->

                isConnectionGenerationCurrent(
                    generation
                )
            },

            onConnected = {
                    generation,
                    socket ->

                if (
                    !isConnected
                ) {

                    setConnected(
                        true
                    )

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "★连接成功★ " +
                                "device=${deviceLogId()} " +
                                "channel=$reconnectChannel " +
                                "localPort=${socket.localPort} " +
                                "generation=$generation"
                    )

                    sendLoginAsync()

                    requestChannelList()

                    startChannelRefreshWorker()

                    if (
                        reconnectChannel.isNotBlank() &&
                        reconnectChannel !=
                        "public"
                    ) {

                        val channelToRestore =
                            reconnectChannel

                        val passwordToRestore =
                            reconnectChannelPassword

                        serviceScope.launch {

                            delay(
                                250L
                            )

                            if (
                                isConnected &&
                                !shuttingDown &&
                                isConnectionGenerationCurrent(
                                    generation
                                )
                            ) {

                                joinChannel(
                                    channelToRestore,
                                    passwordToRestore
                                )
                            }
                        }

                    } else {

                        currentChannel =
                            "public"
                    }

                    broadcastMyUserInfo()

                    broadcastUserList()
                }
            },

            onNetworkPong = {
                    text ->

                handleNetworkPong(
                    text
                )
            },

            onUserOk = {
                    text ->

                handleUserOk(
                    text
                )
            },

            onUserStatus = {
                    text ->

                handleUserStatus(
                    text
                )
            },

            onChannelMembers = {
                    text ->

                handleChannelMembers(
                    text
                )
            },

            onChannelList = {
                    text ->

                handleChannelList(
                    text
                )
            },

            onChannelJoined = {
                    text ->

                handleChannelJoined(
                    text
                )
            },

            onChannelCreated = {
                    text ->

                handleChannelCreated(
                    text
                )
            },

            onChannelDeleted = {
                    text ->

                handleChannelDeleted(
                    text
                )
            },

            onChannelError = {
                    text ->

                handleChannelError(
                    text
                )
            },

            onChannelLeft = {
                    text ->

                handleChannelLeft(
                    text
                )
            },

            handleTalkMessage = {
                    text,
                    generation ->

                walkieTalkManager
                    .handleIncomingMessage(
                        text,
                        generation
                    )
            }
        )
    }

    private val walkieChannelMemberManager by lazy {

        WalkieChannelMemberManager(

            msgChannelMembers =
                MSG_CHANNEL_MEMBERS,

            getCurrentChannel = {
                currentChannel
            },

            getMyUserId = {
                myUserId
            },

            onMembersUpdated = {
                    newUserList ->

                currentUserList.clear()

                currentUserList.addAll(
                    newUserList
                )
            },

            onEmptyMembers = {

                currentUserList.clear()

                currentChannelOnlineCount =
                    0

                broadcastUserList()

                broadcastChannelStatus(
                    "频道：$currentChannel，在线 0 人"
                )
            },

            onMembersCountUpdated = {
                    channelName,
                    count ->

                currentChannelOnlineCount =
                    count

                broadcastUserList()

                broadcastChannelStatus(
                    "频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "在线人员 channel=$channelName " +
                            "count=$count"
                )
            },

            logger = { message ->

                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieRecorder by lazy {

        WalkieRecorder(
            context = applicationContext,
            scope = serviceScope,
            sampleRate = SAMPLE_RATE,
            audioPacketSize = AUDIO_PACKET_SIZE,
            maxOpusPacketSize =
                MAX_OPUS_PACKET_SIZE,
            opusEncoderProvider = {
                opusEncoder
            },
            buildAudioPacket = { opus ->
                audioProtocol.buildPacket(opus)
            },
            sendAudioPacket = { data ->
                udpManager.send(data)
            },
            recordAudioTransmit = { bytes ->
                recordAudioTransmit(bytes)
            },
            isTalkAllowed = {
                talkAllowed
            },
            isConnected = {
                isConnected
            },
            isSpeaking = {
                isSpeaking
            },
            isShuttingDown = {
                shuttingDown
            },
            setSpeaking = { value ->
                isSpeaking = value
            },
            setTalkAllowed = { value ->
                talkAllowed = value
            },
            setTalkRequesting = { value ->
                talkRequesting = value
            },
            setTalkStatus = { status ->
                setTalkStatus(status)
            },
            talkStatusReleased =
                TALK_STATUS_RELEASED,
            sendTalkStop = {
                sendMessageAsync(
                    MSG_TALK_STOP
                )
            },
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieTalkManager by lazy {

        WalkieTalkManager(
            scope = serviceScope,
            isConnected = {
                isConnected
            },
            isChannelSwitching = {
                channelSwitching
            },
            isShuttingDown = {
                shuttingDown
            },
            isTalkRequesting = {
                talkRequesting
            },
            isTalkAllowed = {
                talkAllowed
            },
            isSpeaking = {
                isSpeaking
            },
            setTalkRequesting = { value ->
                talkRequesting = value
            },
            setTalkAllowed = { value ->
                talkAllowed = value
            },
            setSpeaking = { value ->
                isSpeaking = value
            },
            setTalkStatus = { status ->
                setTalkStatus(status)
            },
            talkStatusRequesting =
                TALK_STATUS_REQUESTING,
            talkStatusAllowed =
                TALK_STATUS_ALLOWED,
            talkStatusBusy =
                TALK_STATUS_BUSY,
            talkStatusReleased =
                TALK_STATUS_RELEASED,
            sendTalkStart = {
                sendMessageAsync(
                    MSG_TALK_START
                )
            },
            sendTalkStop = {
                sendMessageAsync(
                    MSG_TALK_STOP
                )
            },
            startRecording = {
                startRecording()
            },
            stopRecording = {
                stopRecording()
            },
            playTalkGrantedTone = {
                playTalkGrantedTone()
            },
            isConnectionGenerationCurrent = {
                    generation ->

                isConnectionGenerationCurrent(
                    generation
                )
            },
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieChannelManager by lazy {

        WalkieChannelManager(
            scope = serviceScope,
            isConnected = {
                isConnected
            },
            isShuttingDown = {
                shuttingDown
            },
            getCurrentChannel = {
                currentChannel
            },
            setCurrentChannel = { value ->
                currentChannel = value
            },
            getReconnectChannel = {
                reconnectChannel
            },
            setReconnectChannel = { value ->
                reconnectChannel = value
            },
            getReconnectChannelPassword = {
                reconnectChannelPassword
            },
            setReconnectChannelPassword = {
                    value ->

                reconnectChannelPassword =
                    value
            },
            getCurrentChannelOnlineCount = {
                currentChannelOnlineCount
            },
            setCurrentChannelOnlineCount = {
                    value ->

                currentChannelOnlineCount =
                    value
            },
            getCurrentChannelPrivate = {
                currentChannelPrivate
            },
            setCurrentChannelPrivate = {
                    value ->

                currentChannelPrivate =
                    value
            },
            getCurrentChannelRequirePassword = {
                currentChannelRequirePassword
            },
            setCurrentChannelRequirePassword = {
                    value ->

                currentChannelRequirePassword =
                    value
            },
            getChannelSwitching = {
                channelSwitching
            },
            setChannelSwitching = { value ->
                channelSwitching =
                    value
            },
            getPendingCreateChannelName = {
                pendingCreateChannelName
            },
            setPendingCreateChannelName = {
                    value ->

                pendingCreateChannelName =
                    value
            },
            getPendingCreateChannelPassword = {
                pendingCreateChannelPassword
            },
            setPendingCreateChannelPassword = {
                    value ->

                pendingCreateChannelPassword =
                    value
            },
            getUserListEmpty = {
                currentUserList.isEmpty()
            },
            clearUserList = {
                clearUserList()
            },
            getCachedChannelInfoList = {
                cachedChannelInfoList
            },
            setCachedChannelInfoList = {
                    value ->

                cachedChannelInfoList =
                    value
            },
            sendMessageAsync = { message ->
                sendMessageAsync(message)
            },
            broadcastChannelList = {
                broadcastChannelList()
            },
            broadcastChannelStatus = {
                    message ->

                broadcastChannelStatus(
                    message
                )
            },
            broadcastChannelDeleted = {
                    deletedChannel ->

                val intent =
                    Intent(
                        ACTION_CHANNEL_DELETED
                    )

                intent.setPackage(
                    packageName
                )

                intent.putExtra(
                    EXTRA_DELETED_CHANNEL,
                    deletedChannel
                )

                sendBroadcast(
                    intent
                )
            },
            updateCurrentChannelInfo = {
                updateCurrentChannelInfo()
            },
            resetTalkState = {
                walkieTalkManager
                    .resetLocalTalkState(
                        updateUi = false
                    )
            },
            requestTalkStatusReset = {
                setTalkStatus(
                    TALK_STATUS_RELEASED
                )
            },
            msgChannelList =
                MSG_CHANNEL_LIST,
            msgChannelMembers =
                MSG_CHANNEL_MEMBERS,
            talkStatusReleased =
                TALK_STATUS_RELEASED,
            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieConnectionManager by lazy {

        WalkieConnectionManager(
            scope = serviceScope,

            udpManager = udpManager,

            logger = { message ->
                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    private val walkieNetworkMigration by lazy {

        WalkieNetworkMigration(
            context = applicationContext,
            scopeLaunch = { block ->

                serviceScope.launch {
                    block()
                }
            },
            logger = { message ->
                println(message)
            },
            getActiveNetwork = {
                activeNetwork
            },
            setActiveNetwork = { value ->
                activeNetwork = value
            },
            getServerIp = {
                serverIp
            },
            getDeviceId = {
                deviceId
            },
            isShuttingDown = {
                shuttingDown
            },
            isConnected = {
                isConnected
            },
            setConnected = { value ->
                setConnected(value)
            },
            setNetworkAvailable = { value ->
                isNetworkAvailable = value
            },
            getNetworkMigrationJob = {
                networkMigrationJob
            },
            setNetworkMigrationJob = { value ->
                networkMigrationJob =
                    value
            },
            startConnection = { ip ->
                startConnection(ip)
            },
            migrateUdp = {
                    ip,
                    port,
                    network ->

                udpManager.migrate(
                    ip = ip,
                    port = port,
                    network = network
                )
            },
            currentUdpSocket = {
                udpManager.currentSocket()
            },
            setUdpSocket = { socket ->
                udpSocket = socket
            },
            sendMessageNow = { message ->
                sendMessageNow(message)
            },
            sendNetworkPing = { now ->
                sendNetworkPing(now)
            },
            closeSocket = {
                closeSocket()
            },
            clearUserList = {
                clearUserList()
            },
            incrementConnectionGenerationAndCancelNetworkJob = {

                synchronized(
                    connectionLock
                ) {

                    connectionGeneration++

                    networkJob?.cancel()

                    networkJob =
                        null
                }
            },
            getConnectionLock = {
                connectionLock
            },
            getServerPort = {
                SERVER_PORT
            },
            getHelloMessage = {
                MSG_HELLO
            },
            getWalkieVersion = {
                WALKIE_VERSION
            }
        )
    }

    private var opusEncoder:
            OpusEncoder? =
        null

    private var opusDecoder:
            OpusDecoder? =
        null

    private var talkRequesting: Boolean
        get() =
            sessionState.talkRequesting
        set(value) {
            sessionState.talkRequesting =
                value
        }

    private var talkAllowed: Boolean
        get() =
            sessionState.talkAllowed
        set(value) {
            sessionState.talkAllowed =
                value
        }

    private var isSpeaking: Boolean
        get() =
            sessionState.isSpeaking
        set(value) {
            sessionState.isSpeaking =
                value
        }

    private var shuttingDown: Boolean
        get() =
            sessionState.shuttingDown
        set(value) {
            sessionState.shuttingDown =
                value
        }

    private var lastKeepAliveTime =
        0L

    private var lastServerActivityTime =
        0L

    private var currentChannel: String
        get() =
            sessionState.currentChannel
        set(value) {
            sessionState.currentChannel =
                value
        }

    private var reconnectChannel: String
        get() =
            sessionState.reconnectChannel
        set(value) {
            sessionState.reconnectChannel =
                value
        }

    private var reconnectChannelPassword: String
        get() =
            sessionState.reconnectChannelPassword
        set(value) {
            sessionState.reconnectChannelPassword =
                value
        }

    private var currentChannelOnlineCount: Int
        get() =
            sessionState.currentChannelOnlineCount
        set(value) {
            sessionState.currentChannelOnlineCount =
                value
        }

    private var currentChannelPrivate: Boolean
        get() =
            sessionState.currentChannelPrivate
        set(value) {
            sessionState.currentChannelPrivate =
                value
        }

    private var currentChannelRequirePassword: Boolean
        get() =
            sessionState.currentChannelRequirePassword
        set(value) {
            sessionState.currentChannelRequirePassword =
                value
        }

    private var channelSwitching: Boolean
        get() =
            sessionState.channelSwitching
        set(value) {
            sessionState.channelSwitching =
                value
        }

    private var cachedChannelInfoList:
            ArrayList<ChannelInfo>
        get() =
            sessionState.cachedChannelInfoList
        set(value) {
            sessionState.cachedChannelInfoList =
                value
        }

    private var pendingCreateChannelName: String
        get() =
            sessionState.pendingCreateChannelName
        set(value) {
            sessionState.pendingCreateChannelName =
                value
        }

    private var pendingCreateChannelPassword: String
        get() =
            sessionState.pendingCreateChannelPassword
        set(value) {
            sessionState.pendingCreateChannelPassword =
                value
        }

    private val walkieServiceLifecycleManager by lazy {

        WalkieServiceLifecycleManager(

            connectionLock =
                connectionLock,

            getConnectionGeneration = {
                connectionGeneration
            },

            setConnectionGeneration = { value ->
                connectionGeneration =
                    value
            },

            getNetworkJob = {
                networkJob
            },

            setNetworkJob = { value ->
                networkJob =
                    value
            },

            getIsConnected = {
                isConnected
            },

            getTalkAllowed = {
                talkAllowed
            },

            getTalkRequesting = {
                talkRequesting
            },

            setShuttingDown = { value ->
                shuttingDown =
                    value
            },

            sendMessageNow = { message ->
                sendMessageNow(
                    message
                )
            },

            msgGoodbye =
                MSG_GOODBYE,

            msgTalkStop =
                MSG_TALK_STOP,

            stopChannelRefreshWorker = {
                stopChannelRefreshWorker()
            },

            stopBackgroundDiagnostic = {
                stopBackgroundDiagnostic()
            },

            stopRecording = {
                stopRecording()
            },

            closeSocket = {
                closeSocket()
            },

            audioPlayback =
                audioPlayback,

            setTalkRequesting = { value ->
                talkRequesting =
                    value
            },

            setTalkAllowed = { value ->
                talkAllowed =
                    value
            },

            setSpeaking = { value ->
                isSpeaking =
                    value
            },

            setConnected = { value ->
                setConnected(
                    value
                )
            },

            clearUserList = {
                clearUserList()
            },

            clearServerUserIdentity = {
                walkieIdentityManager
                    .clearServerUserIdentity()
            },

            setServerIp = { value ->
                serverIp =
                    value
            },

            setServerAddress = { value ->
                serverAddress =
                    value
            },

            setCurrentChannel = { value ->
                currentChannel =
                    value
            },

            setCurrentChannelOnlineCount = {
                    value ->

                currentChannelOnlineCount =
                    value
            },

            setCurrentChannelPrivate = {
                    value ->

                currentChannelPrivate =
                    value
            },

            setCurrentChannelRequirePassword = {
                    value ->

                currentChannelRequirePassword =
                    value
            },

            setReconnectChannel = {
                    value ->

                reconnectChannel =
                    value
            },

            setReconnectChannelPassword = {
                    value ->

                reconnectChannelPassword =
                    value
            },

            setChannelSwitching = {
                    value ->

                channelSwitching =
                    value
            },

            setCachedChannelInfoList = {
                    value ->

                cachedChannelInfoList =
                    value
            },

            setPendingCreateChannelName = {
                    value ->

                pendingCreateChannelName =
                    value
            },

            setPendingCreateChannelPassword = {
                    value ->

                pendingCreateChannelPassword =
                    value
            },

            resetAudioReceiveState = {
                audioProtocol
                    .resetReceiveState()
            },

            resetAudioDecoderFailures = {
                walkieAudioReceiver
                    .reset()
            },

            setTalkStatusReleased = {
                setTalkStatus(
                    TALK_STATUS_RELEASED
                )
            },

            broadcastMyUserInfo = {
                broadcastMyUserInfo()
            },

            broadcastConnectionStatusStopped = {

                val intent =
                    Intent(
                        ACTION_CONNECTION_STATUS
                    )

                intent.setPackage(
                    packageName
                )

                intent.putExtra(
                    EXTRA_CONNECTED,
                    false
                )

                sendBroadcast(
                    intent
                )
            },

            updateNotification = {
                updateNotification()
            },

            logger = { message ->

                println(
                    "WALKIE $WALKIE_VERSION: $message"
                )
            }
        )
    }

    override fun onCreate() {

        super.onCreate()

        walkieIdentityManager.initialize()

        println(
            "WALKIE $WALKIE_VERSION: Service启动 " +
                    "DeviceID=${deviceLogId()} " +
                    "nickname=$nickname"
        )

        initializeOpus()

        walkieConnectionManager.start()

        createNotificationChannel()

        startWalkieForeground()

        acquireWakeLock()

        configureCommunicationAudioOnce()

        registerNetworkCallback()

        startBackgroundDiagnostic()

        println(
            "WALKIE $WALKIE_VERSION: Service started"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {

            ACTION_START -> {

                shuttingDown =
                    false

                if (
                    intent.getBooleanExtra(
                        "TEST_NETWORK_MIGRATION",
                        false
                    )
                ) {

                    testNetworkMigration()

                    return START_STICKY
                }

                val incomingIp =
                    intent.getStringExtra(
                        EXTRA_SERVER_IP
                    )
                        ?.trim()

                val incomingDeviceId =
                    intent.getStringExtra(
                        EXTRA_DEVICE_ID
                    )
                        ?.trim()

                if (
                    !incomingDeviceId.isNullOrBlank()
                ) {

                    deviceId =
                        incomingDeviceId
                }

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "ACTION_START " +
                            "device=${deviceLogId()} " +
                            "nickname=$nickname " +
                            "ip=$incomingIp"
                )

                if (
                    !incomingIp.isNullOrBlank()
                ) {

                    startConnection(
                        incomingIp
                    )
                }
            }

            "TEST_NETWORK_MIGRATION" -> {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "收到手动UDP迁移测试命令"
                )

                testNetworkMigration()
            }

            ACTION_STOP -> {

                stopAll()

                stopSelf()
            }

            ACTION_SPEAK_START -> {

                walkieTalkManager
                    .requestTalk()
            }

            ACTION_SPEAK_STOP -> {

                walkieTalkManager
                    .releaseTalk()
            }

            ACTION_SET_NICKNAME -> {

                val newNickname =
                    intent.getStringExtra(
                        "nickname"
                    )

                if (
                    !newNickname.isNullOrBlank()
                ) {

                    setNickname(
                        newNickname
                    )
                }
            }

            ACTION_CHANNEL_LIST -> {

                requestChannelList()
            }

            ACTION_JOIN_CHANNEL -> {

                val channel =
                    intent.getStringExtra(
                        EXTRA_CHANNEL_NAME
                    )

                val password =
                    intent.getStringExtra(
                        EXTRA_CHANNEL_PASSWORD
                    )
                        ?: ""

                if (
                    !channel.isNullOrBlank()
                ) {

                    joinChannel(
                        channel.trim(),
                        password
                    )
                }
            }

            ACTION_CREATE_CHANNEL -> {

                val channel =
                    intent.getStringExtra(
                        EXTRA_CHANNEL_NAME
                    )

                val password =
                    intent.getStringExtra(
                        EXTRA_CHANNEL_PASSWORD
                    )
                        ?: ""

                val privateChannel =
                    intent.getBooleanExtra(
                        EXTRA_CHANNEL_PRIVATE,
                        false
                    )

                if (
                    !channel.isNullOrBlank()
                ) {

                    createChannel(
                        channel.trim(),
                        password.trim(),
                        privateChannel
                    )
                }
            }

            ACTION_DELETE_CHANNEL -> {

                val channel =
                    intent.getStringExtra(
                        EXTRA_CHANNEL_NAME
                    )

                if (
                    !channel.isNullOrBlank()
                ) {

                    deleteChannel(
                        channel.trim()
                    )
                }
            }
        }

        return START_STICKY
    }

    private fun startWalkieForeground() {

        walkieServiceRuntimeManager
            .startForeground()
    }

    private fun createNotificationChannel() {

        walkieServiceRuntimeManager
            .createNotificationChannel()
    }

    private fun setNickname(
        value: String
    ) {

        walkieIdentityManager
            .setNickname(value)
    }

    private fun cleanNickname(
        value: String
    ):
            String {

        return walkieIdentityManager
            .cleanNickname(value)
    }

    private fun deviceLogId():
            String {

        return walkieIdentityManager
            .deviceLogId()
    }

    private fun sendLoginAsync() {

        walkieIdentityManager
            .sendLoginAsync()
    }

    private fun sendLoginNow() {

        walkieIdentityManager
            .sendLoginNow()
    }

    private fun handleUserOk(
        text: String
    ) {

        walkieIdentityManager
            .handleUserOk(text)
    }

    private fun handleUserStatus(
        text: String
    ) {

        walkieIdentityManager
            .handleUserStatus(text)
    }

    private fun broadcastMyUserInfo() {

        walkieIdentityManager
            .broadcastMyUserInfo()
    }

    private fun broadcastUserList() {

        walkieIdentityManager
            .broadcastUserList()
    }

    private fun clearUserList() {

        walkieIdentityManager
            .clearUserList()
    }

    private fun initializeOpus() {

        try {

            opusEncoder =
                OpusEncoder()

            println(
                "WALKIE $WALKIE_VERSION: OpusEncoder初始化完成"
            )

        } catch (
            e: Throwable
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "OpusEncoder初始化失败=${e.message}"
            )

            opusEncoder =
                null
        }

        try {

            opusDecoder =
                OpusDecoder()

            println(
                "WALKIE $WALKIE_VERSION: OpusDecoder初始化完成"
            )

        } catch (
            e: Throwable
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "OpusDecoder初始化失败=${e.message}"
            )

            opusDecoder =
                null
        }
    }

    private fun startBackgroundDiagnostic() {

        walkieBackgroundDiagnostics
            .start()
    }

    private fun stopBackgroundDiagnostic() {

        walkieBackgroundDiagnostics
            .stop()
    }

    private fun configureCommunicationAudioOnce() {

        walkieAudioRouteManager
            .configureCommunicationAudioOnce()
    }

    private fun setupSpeakerOutput() {
    }

    private fun findBuiltInSpeaker():
            android.media.AudioDeviceInfo? {

        return walkieAudioRouteManager
            .findBuiltInSpeaker()
    }

    private fun registerNetworkCallback() {

        walkieNetworkMigration
            .registerNetworkCallback()
    }

    private fun unregisterNetworkCallback() {

        walkieNetworkMigration
            .unregisterNetworkCallback()
    }

    private fun handleNetworkAvailable(
        network: Network
    ) {

        walkieNetworkMigration
            .handleNetworkAvailable(network)
    }

    private fun testNetworkMigration() {

        walkieNetworkMigration
            .testNetworkMigration()
    }

    private fun handleNetworkLost(
        network: Network
    ) {

        walkieNetworkMigration
            .handleNetworkLost(network)
    }

    private fun handleNetworkCapabilitiesChanged(
        network: Network,
        capabilities: NetworkCapabilities
    ) {

        walkieNetworkMigration
            .handleNetworkCapabilitiesChanged(
                network,
                capabilities
            )
    }

    private fun isConnectionGenerationCurrent(
        generation: Long
    ): Boolean {

        return walkieConnectionManager
            .isConnectionGenerationCurrent(
                generation
            )
    }

    private fun startConnection(
        ip: String
    ) {

        walkieConnectionManager.startConnection(

            ip = ip,

            setServerIp = { value ->
                serverIp =
                    value
            },

            isShuttingDown = {
                shuttingDown
            },

            isConnected = {
                isConnected
            },

            isUdpOpen = {
                udpManager.isOpen()
            },

            runConnectionLoop = {
                    cleanIp,
                    generation ->

                runConnectionLoop(
                    cleanIp,
                    generation
                )
            }
        )
    }

    private suspend fun runConnectionLoop(
        ip: String,
        generation: Long
    ) {

        walkieConnectionManager.runConnectionLoop(

            ip = ip,

            generation = generation,

            isShuttingDown = {
                shuttingDown
            },

            isNetworkAvailable = {
                isNetworkAvailable
            },

            isUdpOpen = {
                udpManager.isOpen()
            },

            isConnected = {
                isConnected
            },

            isConnectionGenerationCurrent = {
                    value ->

                isConnectionGenerationCurrent(
                    value
                )
            },

            cleanupConnection = {
                    value ->

                cleanupConnection(
                    value
                )
            },

            connectOnce = {
                    valueIp,
                    valueGeneration ->

                connectOnce(
                    valueIp,
                    valueGeneration
                )
            }
        )
    }

    private suspend fun connectOnce(
        ip: String,
        generation: Long
    ) {

        if (
            !isConnectionGenerationCurrent(
                generation
            )
        ) {

            return
        }

        val address =
            InetAddress.getByName(
                ip
            )

        if (
            !isConnectionGenerationCurrent(
                generation
            )
        ) {

            return
        }

        serverAddress =
            address

        val network =
            activeNetwork

        val preparedSocket =
            walkieConnectionManager.prepareSocket(

                ip = ip,

                port = SERVER_PORT,

                network = network,

                generation = generation,

                isShuttingDown = {
                    shuttingDown
                },

                isGenerationCurrent = {
                        value ->

                    isConnectionGenerationCurrent(
                        value
                    )
                },

                setCurrentSocket = {
                        socket ->

                    udpSocket =
                        socket
                },

                onSocketOpened = {
                        socket ->

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "UDP Manager创建Socket成功 " +
                                "localPort=${socket.localPort} " +
                                "generation=$generation"
                    )
                }
            )
                ?: return

        var socket:
                DatagramSocket =
            preparedSocket

        try {

            walkieConnectionManager
                .performReconnectHandshake(

                    deviceId =
                        deviceId,

                    walkieVersion =
                        WALKIE_VERSION,

                    isShuttingDown = {
                        shuttingDown
                    },

                    isGenerationCurrent = {
                            value ->

                        isConnectionGenerationCurrent(
                            value
                        )
                    },

                    generation =
                        generation,

                    sendHello = {
                            message ->

                        sendMessageNow(
                            "$MSG_HELLO:$message"
                        )
                    },

                    resetNetworkStats = {
                        resetNetworkStats()
                    },

                    setLastNetworkPingTime = {
                            value ->

                        lastNetworkPingTime =
                            value
                    },

                    setLastKeepAliveTime = {
                            value ->

                        lastKeepAliveTime =
                            value
                    },

                    setLastServerActivityTime = {
                            value ->

                        lastServerActivityTime =
                            value
                    }
                )

            val buffer =
                ByteArray(
                    4096
                )

            while (
                serviceScope.isActive &&
                !shuttingDown &&
                isConnectionGenerationCurrent(
                    generation
                )
            ) {

                val currentManagerSocket =
                    udpManager.currentSocket()

                if (
                    currentManagerSocket == null
                ) {

                    throw SocketException(
                        "UDP Manager当前Socket为空"
                    )
                }

                if (
                    currentManagerSocket !==
                    socket
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "★★★ UDP网络迁移接管 ★★★ " +
                                "oldPort=${socket.localPort} " +
                                "newPort=${currentManagerSocket.localPort}"
                    )

                    socket =
                        currentManagerSocket

                    synchronized(
                        connectionLock
                    ) {

                        udpSocket =
                            currentManagerSocket
                    }
                }

                val currentTime =
                    System.currentTimeMillis()

                val maintenanceOk =
                    walkieNetworkMaintenanceManager
                        .perform(
                            currentTime =
                                currentTime,

                            socket =
                                socket,

                            generation =
                                generation
                        )

                if (
                    !maintenanceOk
                ) {

                    throw SocketException(
                        "server activity timeout"
                    )
                }

                try {

                    var receivedLength =
                        0

                    val text =
                        walkieConnectionManager
                            .receivePacket(

                                buffer =
                                    buffer,

                                receivePacket = {
                                        packet ->

                                    udpManager.receive(
                                        packet
                                    )
                                },

                                onPacketReceived = {
                                        packetLength ->

                                    receivedLength =
                                        packetLength

                                    udpReceiveCount++
                                },

                                onServerActivity = {

                                    lastServerActivityTime =
                                        System.currentTimeMillis()
                                },

                                onPeriodicReceiveLog = {
                                        packetLength ->

                                    if (
                                        udpReceiveCount %
                                        20L ==
                                        0L
                                    ) {

                                        println(
                                            "WALKIE UDP: " +
                                                    "rx count=$udpReceiveCount " +
                                                    "bytes=$packetLength " +
                                                    "port=${socket.localPort}"
                                        )
                                    }
                                }
                            )
                            ?: continue

                    val length =
                        receivedLength

                    val controlMessageHandled =
                        walkieMessageDispatcher
                            .dispatch(
                                text =
                                    text,

                                generation =
                                    generation,

                                socket =
                                    socket
                            )

                    if (
                        controlMessageHandled
                    ) {

                        continue
                    }

                    walkieAudioReceiver
                        .process(
                            buffer =
                                buffer,

                            length =
                                length
                        )
                }

                catch (
                    _: SocketTimeoutException
                ) {

                    continue
                }

                catch (
                    e: SocketException
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "★★★ connectOnce收到SocketException ★★★ " +
                                "socketPort=${socket.localPort} " +
                                "reason=${e.message} " +
                                "generation=$generation"
                    )

                    val currentManagerSocket =
                        udpManager.currentSocket()

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "迁移检查：旧Socket=${socket.localPort} " +
                                "当前ManagerSocket=" +
                                "${currentManagerSocket?.localPort ?: -1} " +
                                "managerOpen=${udpManager.isOpen()}"
                    )

                    if (
                        !shuttingDown &&
                        currentManagerSocket != null &&
                        !currentManagerSocket.isClosed &&
                        currentManagerSocket !==
                        socket &&
                        udpManager.isOpen() &&
                        isConnectionGenerationCurrent(
                            generation
                        )
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "★★★★ 确认是旧Socket正常退场 ★★★★ " +
                                    "oldPort=${socket.localPort} " +
                                    "newPort=${currentManagerSocket.localPort}"
                        )

                        socket =
                            currentManagerSocket

                        synchronized(
                            connectionLock
                        ) {

                            udpSocket =
                                currentManagerSocket
                        }

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "★★★★ connectOnce已经切换到新Socket ★★★★ " +
                                    "port=${socket.localPort}"
                        )

                        continue
                    }

                    if (
                        !shuttingDown &&
                        isConnectionGenerationCurrent(
                            generation
                        )
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "Socket=${e.message} " +
                                    "port=${socket.localPort} " +
                                    "generation=$generation"
                        )
                    }

                    throw e
                }

                catch (
                    e: Throwable
                ) {

                    if (
                        !shuttingDown
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "UDP处理异常=${e.message}，" +
                                    "当前连接将进入自动恢复"
                        )
                    }

                    throw SocketException(
                        "UDP receive processing failed: ${e.message}"
                    )
                }
            }

        } finally {

            val currentSocket =
                synchronized(
                    connectionLock
                ) {

                    udpSocket
                }

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "connectOnce进入finally " +
                        "socketPort=${socket.localPort} " +
                        "currentPort=${currentSocket?.localPort ?: -1} " +
                        "managerOpen=${udpManager.isOpen()} " +
                        "generation=$generation"
            )

            if (
                currentSocket ===
                socket
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "connectOnce确认当前Socket仍是自己，" +
                            "准备关闭UDP Manager " +
                            "port=${socket.localPort}"
                )

                try {

                    udpManager.close()

                } catch (
                    e: Throwable
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "关闭UDP Manager异常=${e.message}"
                    )
                }

                synchronized(
                    connectionLock
                ) {

                    if (
                        udpSocket ===
                        socket
                    ) {

                        udpSocket =
                            null
                    }
                }

            } else {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "★★★★ finally确认已被新Socket接管，" +
                            "绝不关闭新Socket ★★★★ " +
                            "oldPort=${socket.localPort} " +
                            "newPort=${currentSocket?.localPort ?: -1}"
                )
            }
        }
    }

    private fun handleChannelMembers(
        text: String
    ) {

        walkieChannelMemberManager
            .handle(
                text
            )
    }

    private fun requestChannelList() {

        walkieChannelManager
            .requestChannelList()
    }

    private fun startChannelRefreshWorker() {

        walkieChannelManager
            .startChannelRefreshWorker()
    }

    private fun stopChannelRefreshWorker() {

        walkieChannelManager
            .stopChannelRefreshWorker()
    }

    private fun handleChannelList(
        text: String
    ) {

        walkieChannelManager
            .handleChannelList(text)
    }

    private fun broadcastChannelList() {

        val intent =
            Intent(
                ACTION_CHANNEL_LIST
            )

        intent.setPackage(
            packageName
        )

        val names =
            ArrayList<String>()

        val infos =
            ArrayList<String>()

        for (
        channel in
        cachedChannelInfoList
        ) {

            names.add(
                channel.name
            )

            infos.add(
                channel.name +
                        "," +
                        if (
                            channel.isPrivate
                        ) {

                            "PRIVATE"

                        } else {

                            "PUBLIC"
                        } +
                        "," +
                        channel.onlineCount
            )
        }

        intent.putStringArrayListExtra(
            EXTRA_CHANNEL_LIST,
            names
        )

        intent.putStringArrayListExtra(
            EXTRA_CHANNEL_INFO,
            infos
        )

        sendBroadcast(
            intent
        )
    }

    private fun createChannel(
        channel: String,
        password: String,
        privateChannel: Boolean
    ) {

        walkieChannelManager
            .createChannel(
                channel,
                password,
                privateChannel
            )
    }

    private fun joinChannel(
        channel: String,
        password: String
    ) {

        walkieChannelManager
            .joinChannel(
                channel,
                password
            )
    }

    private fun handleChannelJoined(
        text: String
    ) {

        walkieChannelManager
            .handleChannelJoined(text)
    }

    private fun handleChannelCreated(
        text: String
    ) {

        walkieChannelManager
            .handleChannelCreated(text)
    }

    private fun handleChannelDeleted(
        text: String
    ) {

        walkieChannelManager
            .handleChannelDeleted(text)
    }

    private fun handleChannelError(
        text: String
    ) {

        walkieChannelManager
            .handleChannelError(text)
    }

    private fun handleChannelLeft(
        text: String
    ) {

        walkieChannelManager
            .handleChannelLeft(text)
    }

    private fun updateCurrentChannelInfo() {

        walkieChannelManager
            .updateCurrentChannelInfo()
    }

    private fun broadcastChannelStatus(
        message: String
    ) {

        val intent =
            Intent(
                ACTION_CHANNEL_STATUS
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            EXTRA_CURRENT_CHANNEL,
            currentChannel
        )

        intent.putExtra(
            EXTRA_CHANNEL_MESSAGE,
            message
        )

        intent.putExtra(
            EXTRA_CHANNEL_ONLINE_COUNT,
            currentChannelOnlineCount
        )

        intent.putExtra(
            EXTRA_CHANNEL_PRIVATE,
            currentChannelPrivate
        )

        intent.putExtra(
            EXTRA_CHANNEL_REQUIRE_PASSWORD,
            currentChannelRequirePassword
        )

        sendBroadcast(
            intent
        )

        updateNotification()
    }

    private fun deleteChannel(
        channel: String
    ) {

        walkieChannelManager
            .deleteChannel(
                channel
            )
    }

    private fun sendMessageNow(
        message: String
    ) {

        if (
            shuttingDown &&
            message != MSG_GOODBYE &&
            message != MSG_TALK_STOP
        ) {

            return
        }

        try {

            udpManager.send(
                message
            )

        } catch (
            e: Throwable
        ) {

            if (
                !shuttingDown
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "UDP Manager发送失败=${e.message}"
                )
            }
        }
    }

    private fun sendMessageAsync(
        message: String
    ) {

        if (
            shuttingDown &&
            message != MSG_GOODBYE &&
            message != MSG_TALK_STOP
        ) {

            return
        }

        serviceScope.launch {

            sendMessageNow(
                message
            )
        }
    }

    private fun playTalkGrantedTone() {

        serviceScope.launch(
            Dispatchers.Main.immediate
        ) {

            var toneGenerator:
                    ToneGenerator? =
                null

            try {

                toneGenerator =
                    ToneGenerator(
                        AudioManager.STREAM_MUSIC,
                        100
                    )

                toneGenerator.startTone(
                    ToneGenerator.TONE_PROP_ACK,
                    120
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "★抢麦成功提示音★"
                )

                delay(
                    150L
                )

            } catch (
                e: Throwable
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "抢麦提示音失败=${e.message}"
                )

            } finally {

                try {

                    toneGenerator?.release()

                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun startRecording() {

        walkieRecorder
            .startRecording()
    }

    private fun stopRecording() {

        walkieRecorder
            .stopRecording()
    }

    private fun resetNetworkStats() {

        networkStats.reset(
            System.currentTimeMillis()
        )
    }

    private fun sendNetworkPing(
        now: Long
    ) {

        networkStats.sendPing(
            now
        )
    }

    private fun handleNetworkPong(
        text: String
    ) {

        networkStats.handlePong(
            text,
            System.currentTimeMillis()
        )
    }

    private fun expireNetworkPings(
        now: Long
    ) {

        networkStats.expirePings(
            now
        )
    }

    private fun updateNetworkQuality() {

        networkStats.updateQuality()
    }

    private fun adaptPlaybackBuffer() {

        networkStats.adaptPlaybackBuffer()
    }

    private fun recordAudioTransmit(
        bytes: Int
    ) {

        networkStats.recordAudioTransmit(
            bytes
        )
    }

    private fun recordAudioReceive(
        bytes: Int
    ) {

        networkStats.recordAudioReceive(
            bytes
        )
    }

    private fun updateNetworkBitrate(
        now: Long
    ) {

        networkStats.updateBitrate(
            now
        )
    }

    private fun broadcastNetworkStatus(
        force: Boolean
    ) {

        networkStats.broadcastStatus(
            force
        )
    }

    private fun setConnected(
        connected: Boolean
    ) {

        if (
            isConnected ==
            connected
        ) {

            updateNotification()

            return
        }

        isConnected =
            connected

        if (
            !connected
        ) {

            resetNetworkStats()

            stopChannelRefreshWorker()

            clearUserList()

            walkieIdentityManager
                .clearServerUserIdentity()
        }

        val intent =
            Intent(
                ACTION_CONNECTION_STATUS
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            EXTRA_CONNECTED,
            connected
        )

        sendBroadcast(
            intent
        )

        updateNotification()
    }

    private fun setTalkStatus(
        status: String
    ) {

        val intent =
            Intent(
                ACTION_TALK_STATUS
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            EXTRA_TALK_STATUS,
            status
        )

        sendBroadcast(
            intent
        )
    }

    private fun updateNotification() {

        walkieServiceRuntimeManager
            .updateNotification()
    }

    private fun acquireWakeLock() {

        walkieServiceRuntimeManager
            .acquireWakeLock()
    }

    private fun cleanupSocket(
        socket: DatagramSocket
    ) {

        walkieConnectionManager
            .cleanupSocket(

                socket =
                    socket,

                getCurrentSocket = {

                    synchronized(
                        connectionLock
                    ) {

                        udpSocket
                    }
                },

                clearCurrentSocket = {

                    synchronized(
                        connectionLock
                    ) {

                        if (
                            udpSocket ===
                            socket
                        ) {

                            udpSocket =
                                null
                        }
                    }
                }
            )
    }

    private fun cleanupConnection(
        generation: Long
    ) {

        walkieConnectionManager
            .cleanupConnection(

                generation =
                    generation,

                isGenerationCurrent = {
                        value ->

                    isConnectionGenerationCurrent(
                        value
                    )
                },

                getCurrentChannel = {

                    currentChannel
                },

                setReconnectChannel = {
                        value ->

                    reconnectChannel =
                        value
                },

                clearTalkState = {

                    talkRequesting =
                        false

                    talkAllowed =
                        false

                    isSpeaking =
                        false
                },

                stopRecording = {

                    stopRecording()
                },

                closeSocket = {

                    closeSocket()
                },

                clearServerAddress = {

                    serverAddress =
                        null
                },

                resetAudioReceiveState = {

                    audioProtocol
                        .resetReceiveState()
                },

                resetDecodeFailures = {

                    walkieAudioReceiver
                        .reset()
                },

                clearPlaybackQueue = {

                    audioPlayback
                        .clearQueue()
                },

                requestPlaybackRecovery = {

                    audioPlayback
                        .requestRecovery()
                },

                setCurrentChannel = {
                        value ->

                    currentChannel =
                        value
                },

                setOnlineCount = {
                        value ->

                    currentChannelOnlineCount =
                        value
                },

                setCurrentChannelPrivate = {
                        value ->

                    currentChannelPrivate =
                        value
                },

                setCurrentChannelRequirePassword = {
                        value ->

                    currentChannelRequirePassword =
                        value
                },

                setChannelSwitching = {
                        value ->

                    channelSwitching =
                        value
                },

                clearCachedChannelInfo = {

                    cachedChannelInfoList =
                        ArrayList()
                },

                clearUserList = {

                    clearUserList()
                },

                setTalkStatusReleased = {

                    setTalkStatus(
                        TALK_STATUS_RELEASED
                    )
                },

                setConnected = {
                        value ->

                    setConnected(
                        value
                    )
                },

                logger = {
                        message ->

                    println(
                        "WALKIE $WALKIE_VERSION: $message"
                    )
                }
            )
    }

    private fun closeSocket() {

        walkieConnectionManager
            .closeSocket(

                clearCurrentSocket = {

                    synchronized(
                        connectionLock
                    ) {

                        udpSocket =
                            null
                    }
                }
            )

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "UDP Manager 已关闭"
        )
    }

    private fun stopAll() {

        walkieServiceLifecycleManager
            .stopAll()
    }

    private fun onServiceRuntimeStop() {

        walkieServiceRuntimeManager
            .stop()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    override fun onDestroy() {

        println(
            "WALKIE $WALKIE_VERSION: Service destroyed"
        )

        stopAll()

        unregisterNetworkCallback()

        try {

            opusEncoder?.release()

        } catch (_: Exception) {
        }

        try {

            opusDecoder?.release()

        } catch (_: Exception) {
        }

        opusEncoder =
            null

        opusDecoder =
            null

        onServiceRuntimeStop()

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val audioManager =
                    getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                audioManager
                    .clearCommunicationDevice()
            }

        } catch (_: Exception) {
        }

        walkieConnectionManager
            .stop()

        serviceScope.cancel()

        super.onDestroy()
    }
}