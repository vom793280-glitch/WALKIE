package com.example.walkie

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WalkieService : Service() {

    companion object {

        private const val WALKIE_VERSION = "V20"

        const val ACTION_START =
            "com.example.walkie.ACTION_START"

        const val ACTION_STOP =
            "com.example.walkie.ACTION_STOP"

        const val ACTION_SPEAK_START =
            "com.example.walkie.ACTION_SPEAK_START"

        const val ACTION_SPEAK_STOP =
            "com.example.walkie.ACTION_SPEAK_STOP"

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

        /*
         * 20ms PCM
         * 16000 Hz * 20ms = 320 samples
         * 320 * 2 = 640 bytes
         */
        private const val AUDIO_PACKET_SIZE =
            640

        private const val KEEP_ALIVE_INTERVAL =
            5000L

        private const val SOCKET_RECEIVE_TIMEOUT =
            500

        private const val SERVER_ACTIVITY_TIMEOUT =
            30000L

        private const val INITIAL_RECONNECT_INTERVAL =
            300L

        private const val MAX_RECONNECT_INTERVAL =
            1500L

        /*
         * ============================================================
         * V19 播放缓冲
         * ============================================================
         *
         * 重点修改：
         *
         * V18 = 5包再起播
         * V19 = 2包再起播
         *
         * 每包约20ms。
         *
         * 2包约40ms。
         *
         * 目的是减少讲话开头被等待缓冲时间吃掉的问题。
         */
        private const val PLAYBACK_QUEUE_CAPACITY =
            40

        private const val PLAYBACK_START_BUFFER_PACKETS =
            2

        /*
         * UNDERRUN 后只等待3包再恢复，
         * 不把等待时间做得过长。
         */
        private const val PLAYBACK_RECOVERY_BUFFER_PACKETS =
            3

        /*
         * 超过24包就丢最旧数据，
         * 防止弱网恢复后延迟越积越大。
         */
        private const val PLAYBACK_MAX_QUEUE_PACKETS =
            24

        private const val PLAYBACK_GAIN =
            1.0f

        private const val MAX_OPUS_PACKET_SIZE =
            1208

        private const val MAX_DECODED_PCM_SAMPLES =
            4096

        private const val DEVICE_PREFS_NAME =
            "walkie_device_identity"

        private const val DEVICE_ID_KEY =
            "device_id"

        private const val MSG_HELLO =
            "WALKIE_HELLO"

        private const val MSG_CONNECTED =
            "WALKIE_CONNECTED"

        private const val MSG_KEEP_ALIVE =
            "WALKIE_KEEPALIVE"

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
    }

    data class ChannelInfo(
        val name: String,
        val onlineCount: Int,
        val isPrivate: Boolean
    )

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    private lateinit var devicePreferences:
            SharedPreferences

    @Volatile
    private var deviceId =
        ""

    @Volatile
    private var udpSocket:
            DatagramSocket? =
        null

    @Volatile
    private var serverAddress:
            InetAddress? =
        null

    @Volatile
    private var serverIp:
            String? =
        null

    @Volatile
    private var isConnected =
        false

    @Volatile
    private var isNetworkAvailable =
        true

    @Volatile
    private var activeNetwork:
            Network? =
        null

    private var networkCallback:
            ConnectivityManager.NetworkCallback? =
        null

    private var networkJob:
            Job? =
        null

    private var channelRefreshJob:
            Job? =
        null

    private var backgroundDiagnosticJob:
            Job? =
        null

    @Volatile
    private var backgroundHeartbeatCount =
        0L

    @Volatile
    private var udpKeepAliveCount =
        0L

    @Volatile
    private var udpReceiveCount =
        0L

    /*
     * ============================================================
     * 播放
     * ============================================================
     */

    @Volatile
    private var audioTrack:
            AudioTrack? =
        null

    private var playbackJob:
            Job? =
        null

    private val playbackQueue =
        ArrayBlockingQueue<ByteArray>(
            PLAYBACK_QUEUE_CAPACITY
        )

    private val audioTrackLock =
        Any()

    private val playbackWorkerLock =
        Any()

    @Volatile
    private var playbackWorkerStarting =
        false

    @Volatile
    private var playbackRecoveryRequested =
        false

    @Volatile
    private var lastUnderrunCount =
        0

    /*
     * ============================================================
     * 录音
     * ============================================================
     */

    @Volatile
    private var audioRecord:
            AudioRecord? =
        null

    private var recordJob:
            Job? =
        null

    private val audioRecordLock =
        Any()

    private var noiseSuppressor:
            NoiseSuppressor? =
        null

    private var automaticGainControl:
            AutomaticGainControl? =
        null

    private var acousticEchoCanceler:
            AcousticEchoCanceler? =
        null

    /*
     * ============================================================
     * Opus
     * ============================================================
     */

    private var opusEncoder:
            OpusEncoder? =
        null

    private var opusDecoder:
            OpusDecoder? =
        null

    /*
     * ============================================================
     * 状态
     * ============================================================
     */

    @Volatile
    private var talkRequesting =
        false

    @Volatile
    private var talkAllowed =
        false

    @Volatile
    private var isSpeaking =
        false

    @Volatile
    private var shuttingDown =
        false

    @Volatile
    private var lastKeepAliveTime =
        0L

    @Volatile
    private var lastServerActivityTime =
        0L

    @Volatile
    private var currentChannel =
        "public"

    @Volatile
    private var reconnectChannel =
        ""

    @Volatile
    private var reconnectChannelPassword =
        ""

    @Volatile
    private var currentChannelOnlineCount =
        0

    @Volatile
    private var currentChannelPrivate =
        false

    @Volatile
    private var currentChannelRequirePassword =
        false

    @Volatile
    private var channelSwitching =
        false

    @Volatile
    private var cachedChannelInfoList =
        ArrayList<ChannelInfo>()

    private var pendingCreateChannelName =
        ""

    private var pendingCreateChannelPassword =
        ""

    private var wakeLock:
            PowerManager.WakeLock? =
        null

    /*
     * ============================================================
     * Service
     * ============================================================
     */

    override fun onCreate() {

        super.onCreate()

        devicePreferences =
            getSharedPreferences(
                DEVICE_PREFS_NAME,
                Context.MODE_PRIVATE
            )

        deviceId =
            loadOrCreateDeviceId()

        println(
            "WALKIE $WALKIE_VERSION: Service启动 DeviceID=${deviceLogId()}"
        )

        initializeOpus()

        createNotificationChannel()

        startWalkieForeground()

        acquireWakeLock()

        /*
         * 只在Service启动时初始化通信音频环境。
         */
        configureCommunicationAudioOnce()

        registerNetworkCallback()

        startBackgroundDiagnostic()

        println(
            "WALKIE $WALKIE_VERSION: Service started"
        )
    }

    /*
     * ============================================================
     * 前台服务
     * ============================================================
     */

    private fun startWalkieForeground() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )

            } else {

                startForeground(
                    NOTIFICATION_ID,
                    createNotification()
                )
            }

            println(
                "WALKIE $WALKIE_VERSION: 前台服务启动成功"
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: 前台服务启动失败=${e.message}"
            )
        }
    }

    /*
     * ============================================================
     * DeviceID
     * ============================================================
     */

    private fun loadOrCreateDeviceId():
            String {

        val saved =
            try {

                devicePreferences
                    .getString(
                        DEVICE_ID_KEY,
                        null
                    )
                    ?.trim()

            } catch (_: Exception) {

                null
            }

        if (
            !saved.isNullOrBlank()
        ) {

            return saved
        }

        val newId =
            "WALKIE-" +
                    UUID.randomUUID()
                        .toString()
                        .replace(
                            "-",
                            ""
                        )
                        .uppercase()

        try {

            devicePreferences
                .edit()
                .putString(
                    DEVICE_ID_KEY,
                    newId
                )
                .apply()

        } catch (_: Exception) {
        }

        return newId
    }

    private fun deviceLogId():
            String {

        return if (
            deviceId.length > 8
        ) {

            deviceId.take(8) +
                    "..."

        } else {

            deviceId
        }
    }

    /*
     * ============================================================
     * Opus
     * ============================================================
     */

    private fun initializeOpus() {

        try {

            opusEncoder =
                OpusEncoder()

            println(
                "WALKIE $WALKIE_VERSION: OpusEncoder初始化完成"
            )

        } catch (e: Throwable) {

            println(
                "WALKIE $WALKIE_VERSION: OpusEncoder初始化失败=${e.message}"
            )

            opusEncoder = null
        }

        try {

            opusDecoder =
                OpusDecoder()

            println(
                "WALKIE $WALKIE_VERSION: OpusDecoder初始化完成"
            )

        } catch (e: Throwable) {

            println(
                "WALKIE $WALKIE_VERSION: OpusDecoder初始化失败=${e.message}"
            )

            opusDecoder = null
        }
    }

    /*
     * ============================================================
     * 后台诊断
     * ============================================================
     */

    private fun startBackgroundDiagnostic() {

        if (
            backgroundDiagnosticJob?.isActive == true
        ) {

            return
        }

        backgroundDiagnosticJob =
            serviceScope.launch {

                while (
                    serviceScope.isActive &&
                    !shuttingDown
                ) {

                    delay(5000L)

                    backgroundHeartbeatCount++

                    println(
                        "WALKIE BG: alive " +
                                "count=$backgroundHeartbeatCount " +
                                "connected=$isConnected " +
                                "keepalive=$udpKeepAliveCount " +
                                "rx=$udpReceiveCount " +
                                "queue=${playbackQueue.size} " +
                                "device=${deviceLogId()}"
                    )
                }
            }
    }

    private fun stopBackgroundDiagnostic() {

        backgroundDiagnosticJob?.cancel()

        backgroundDiagnosticJob =
            null
    }

    /*
     * ============================================================
     * 音频通信路由
     * ============================================================
     */

    private fun configureCommunicationAudioOnce() {

        try {

            val audioManager =
                getSystemService(
                    Context.AUDIO_SERVICE
                ) as AudioManager

            @Suppress("DEPRECATION")
            audioManager.mode =
                AudioManager.MODE_IN_COMMUNICATION

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val speaker =
                    audioManager
                        .availableCommunicationDevices
                        .firstOrNull {
                            it.type ==
                                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }

                if (
                    speaker != null
                ) {

                    try {

                        val result =
                            audioManager
                                .setCommunicationDevice(
                                    speaker
                                )

                        println(
                            "WALKIE AUDIO: 初始化通信扬声器 result=$result"
                        )

                    } catch (e: Exception) {

                        println(
                            "WALKIE AUDIO: 设置通信扬声器失败=${e.message}"
                        )
                    }

                } else {

                    println(
                        "WALKIE AUDIO: 未找到通信扬声器"
                    )
                }

            } else {

                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn =
                    true

                println(
                    "WALKIE AUDIO: 旧系统扬声器已开启"
                )
            }

        } catch (e: Exception) {

            println(
                "WALKIE AUDIO: 初始化音频路由异常=${e.message}"
            )
        }
    }

    /*
     * 兼容旧代码。
     *
     * V19 不在录音结束、AudioTrack创建等地方
     * 反复修改 AudioManager.mode。
     */
    private fun setupSpeakerOutput() {
    }

    private fun findBuiltInSpeaker():
            AudioDeviceInfo? {

        return try {

            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.M
            ) {

                null

            } else {

                val audioManager =
                    getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                audioManager
                    .getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    )
                    .firstOrNull {
                        it.type ==
                                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
            }

        } catch (_: Exception) {

            null
        }
    }

    /*
     * ============================================================
     * 网络
     * ============================================================
     */

    private fun registerNetworkCallback() {

        if (
            networkCallback != null
        ) {

            return
        }

        val connectivityManager =
            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        networkCallback =
            object :
                ConnectivityManager.NetworkCallback() {

                override fun onAvailable(
                    network: Network
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: 网络可用=$network"
                    )

                    activeNetwork =
                        network

                    isNetworkAvailable =
                        true

                    val ip =
                        serverIp

                    if (
                        !shuttingDown &&
                        !ip.isNullOrBlank()
                    ) {

                        serviceScope.launch {

                            delay(150L)

                            if (
                                shuttingDown
                            ) {

                                return@launch
                            }

                            if (
                                isConnected &&
                                udpSocket?.isClosed == false
                            ) {

                                return@launch
                            }

                            closeSocket()

                            networkJob?.cancel()

                            networkJob =
                                null

                            startConnection(ip)
                        }
                    }
                }

                override fun onLost(
                    network: Network
                ) {

                    if (
                        activeNetwork !=
                        network
                    ) {

                        return
                    }

                    println(
                        "WALKIE $WALKIE_VERSION: 当前网络丢失=$network"
                    )

                    activeNetwork =
                        null

                    isNetworkAvailable =
                        false

                    setConnected(false)

                    closeSocket()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {

                    if (
                        activeNetwork ==
                        network
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: 网络能力变化"
                        )
                    }
                }
            }

        try {

            connectivityManager
                .registerDefaultNetworkCallback(
                    networkCallback!!
                )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: 网络监听失败=${e.message}"
            )

            networkCallback =
                null
        }
    }

    private fun unregisterNetworkCallback() {

        val callback =
            networkCallback
                ?: return

        try {

            val connectivityManager =
                getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            connectivityManager
                .unregisterNetworkCallback(
                    callback
                )

        } catch (_: Exception) {
        }

        networkCallback =
            null

        activeNetwork =
            null
    }

    /*
     * ============================================================
     * onStartCommand
     * ============================================================
     */

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

                val incomingIp =
                    intent.getStringExtra(
                        EXTRA_SERVER_IP
                    )

                val incomingDeviceId =
                    intent.getStringExtra(
                        EXTRA_DEVICE_ID
                    )?.trim()

                if (
                    !incomingDeviceId.isNullOrBlank()
                ) {

                    deviceId =
                        incomingDeviceId

                    try {

                        devicePreferences
                            .edit()
                            .putString(
                                DEVICE_ID_KEY,
                                deviceId
                            )
                            .apply()

                    } catch (_: Exception) {
                    }
                }

                println(
                    "WALKIE $WALKIE_VERSION: ACTION_START device=${deviceLogId()}"
                )

                if (
                    !incomingIp.isNullOrBlank()
                ) {

                    startConnection(
                        incomingIp.trim()
                    )
                }
            }

            ACTION_STOP -> {

                stopAll()

                stopSelf()
            }

            ACTION_SPEAK_START -> {

                requestTalk()
            }

            ACTION_SPEAK_STOP -> {

                releaseTalk()
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
                    ) ?: ""

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
                    ) ?: ""

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

    /*
     * ============================================================
     * 连接
     * ============================================================
     */

    private fun startConnection(
        ip: String
    ) {

        serverIp =
            ip

        if (
            networkJob?.isActive == true
        ) {

            return
        }

        println(
            "WALKIE $WALKIE_VERSION: 开始连接 $ip:$SERVER_PORT device=${deviceLogId()}"
        )

        networkJob =
            serviceScope.launch {

                var reconnectDelay =
                    INITIAL_RECONNECT_INTERVAL

                while (
                    serviceScope.isActive &&
                    !shuttingDown
                ) {

                    try {

                        connectOnce(ip)

                        reconnectDelay =
                            INITIAL_RECONNECT_INTERVAL

                    } catch (e: Exception) {

                        if (
                            !shuttingDown
                        ) {

                            println(
                                "WALKIE $WALKIE_VERSION: 网络异常=${e.message}"
                            )
                        }
                    }

                    if (
                        !serviceScope.isActive ||
                        shuttingDown
                    ) {

                        break
                    }

                    cleanupConnection()

                    if (
                        !isNetworkAvailable
                    ) {

                        delay(1000L)

                        continue
                    }

                    delay(
                        reconnectDelay
                    )

                    reconnectDelay =
                        if (
                            reconnectDelay * 2L >
                            MAX_RECONNECT_INTERVAL
                        ) {

                            MAX_RECONNECT_INTERVAL

                        } else {

                            reconnectDelay * 2L
                        }
                }
            }
    }

    /*
     * ============================================================
     * UDP
     * ============================================================
     */

    private fun connectOnce(
        ip: String
    ) {

        val address =
            InetAddress.getByName(ip)

        serverAddress =
            address

        val socket =
            DatagramSocket()

        socket.soTimeout =
            SOCKET_RECEIVE_TIMEOUT

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

        udpSocket =
            socket

        println(
            "WALKIE $WALKIE_VERSION: UDP localPort=${socket.localPort}"
        )

        try {

            createAudioPlayer()

            sendMessageNow(
                "$MSG_HELLO:$deviceId"
            )

            val now =
                System.currentTimeMillis()

            lastKeepAliveTime =
                now

            lastServerActivityTime =
                now

            val buffer =
                ByteArray(4096)

            while (
                serviceScope.isActive &&
                !shuttingDown &&
                !socket.isClosed
            ) {

                val currentTime =
                    System.currentTimeMillis()

                if (
                    currentTime -
                    lastKeepAliveTime >=
                    KEEP_ALIVE_INTERVAL
                ) {

                    sendMessageNow(
                        MSG_KEEP_ALIVE
                    )

                    udpKeepAliveCount++

                    println(
                        "WALKIE UDP: keepalive count=$udpKeepAliveCount"
                    )

                    lastKeepAliveTime =
                        currentTime
                }

                if (
                    isConnected &&
                    currentTime -
                    lastServerActivityTime >=
                    SERVER_ACTIVITY_TIMEOUT
                ) {

                    println(
                        "WALKIE UDP: server timeout"
                    )

                    throw SocketException(
                        "server activity timeout"
                    )
                }

                try {

                    val packet =
                        DatagramPacket(
                            buffer,
                            buffer.size
                        )

                    packet.length =
                        buffer.size

                    socket.receive(
                        packet
                    )

                    val length =
                        packet.length

                    if (
                        length <= 0
                    ) {

                        continue
                    }

                    udpReceiveCount++

                    lastServerActivityTime =
                        System.currentTimeMillis()

                    if (
                        udpReceiveCount % 20L ==
                        0L
                    ) {

                        println(
                            "WALKIE UDP: rx count=$udpReceiveCount bytes=$length"
                        )
                    }

                    val text =
                        String(
                            packet.data,
                            packet.offset,
                            length,
                            Charsets.UTF_8
                        )

                    if (
                        text ==
                        MSG_CONNECTED
                    ) {

                        if (
                            !isConnected
                        ) {

                            setConnected(true)

                            println(
                                "WALKIE $WALKIE_VERSION: ★连接成功★ device=${deviceLogId()} channel=$reconnectChannel"
                            )

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

                                    delay(250L)

                                    if (
                                        isConnected &&
                                        !shuttingDown
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
                        }

                        continue
                    }

                    if (
                        text ==
                        MSG_KEEP_ALIVE
                    ) {

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_LIST:"
                        )
                    ) {

                        handleChannelList(text)

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_JOINED:"
                        )
                    ) {

                        handleChannelJoined(text)

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_CREATED:"
                        )
                    ) {

                        handleChannelCreated(text)

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_DELETED:"
                        )
                    ) {

                        handleChannelDeleted(text)

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_ERROR:"
                        )
                    ) {

                        handleChannelError(text)

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_LEFT:"
                        )
                    ) {

                        handleChannelLeft(text)

                        continue
                    }

                    if (
                        text ==
                        MSG_TALK_OK
                    ) {

                        if (
                            talkRequesting &&
                            isConnected
                        ) {

                            talkRequesting =
                                false

                            talkAllowed =
                                true

                            setTalkStatus(
                                TALK_STATUS_ALLOWED
                            )

                            /*
                             * 抢麦成功以后立即启动录音。
                             */
                            startRecording()
                        }

                        continue
                    }

                    if (
                        text ==
                        MSG_TALK_BUSY
                    ) {

                        talkRequesting =
                            false

                        talkAllowed =
                            false

                        isSpeaking =
                            false

                        stopRecording()

                        setTalkStatus(
                            TALK_STATUS_BUSY
                        )

                        continue
                    }

                    if (
                        text ==
                        MSG_TALK_RELEASED
                    ) {

                        talkRequesting =
                            false

                        talkAllowed =
                            false

                        isSpeaking =
                            false

                        stopRecording()

                        setTalkStatus(
                            TALK_STATUS_RELEASED
                        )

                        continue
                    }

                    /*
                     * 所有 WALKIE_ 开头的控制包
                     * 不进入 Opus 解码。
                     */
                    if (
                        text.startsWith(
                            "WALKIE_"
                        )
                    ) {

                        continue
                    }

                    /*
                     * ==================================================
                     * 音频包
                     * ==================================================
                     */

                    if (
                        length <= 0 ||
                        length >
                        MAX_OPUS_PACKET_SIZE
                    ) {

                        println(
                            "WALKIE AUDIO: drop invalid packet size=$length"
                        )

                        continue
                    }

                    val decoder =
                        opusDecoder
                            ?: continue

                    val audioData =
                        ByteArray(length)

                    System.arraycopy(
                        packet.data,
                        packet.offset,
                        audioData,
                        0,
                        length
                    )

                    val pcmData =
                        try {

                            decoder.decode(
                                audioData
                            )

                        } catch (e: Throwable) {

                            println(
                                "WALKIE AUDIO: decoder exception=${e.message}"
                            )

                            null
                        }

                    if (
                        pcmData == null ||
                        pcmData.isEmpty()
                    ) {

                        continue
                    }

                    if (
                        pcmData.size >
                        MAX_DECODED_PCM_SAMPLES
                    ) {

                        println(
                            "WALKIE AUDIO: drop invalid PCM=${pcmData.size}"
                        )

                        continue
                    }

                    val pcmBytes =
                        ByteArray(
                            pcmData.size * 2
                        )

                    var i =
                        0

                    while (
                        i <
                        pcmData.size
                    ) {

                        val sample =
                            pcmData[i].toInt()

                        pcmBytes[i * 2] =
                            (
                                    sample and
                                            0xff
                                    ).toByte()

                        pcmBytes[i * 2 + 1] =
                            (
                                    (sample shr 8) and
                                            0xff
                                    ).toByte()

                        i++
                    }

                    enqueueAudio(
                        pcmBytes
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

                    if (
                        !shuttingDown
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: Socket=${e.message}"
                        )
                    }

                    throw e
                }

                catch (
                    e: Throwable
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: UDP处理异常=${e.message}"
                    )
                }
            }

        } finally {

            cleanupSocket(socket)
        }
    }

    /*
     * ============================================================
     * 频道
     * ============================================================
     */

    private fun requestChannelList() {

        if (
            !isConnected
        ) {

            return
        }

        sendMessageAsync(
            MSG_CHANNEL_LIST
        )
    }

    private fun startChannelRefreshWorker() {

        if (
            channelRefreshJob?.isActive == true
        ) {

            return
        }

        channelRefreshJob =
            serviceScope.launch {

                while (
                    serviceScope.isActive &&
                    !shuttingDown
                ) {

                    delay(10000L)

                    if (
                        isConnected
                    ) {

                        requestChannelList()
                    }
                }
            }
    }

    private fun stopChannelRefreshWorker() {

        channelRefreshJob?.cancel()

        channelRefreshJob =
            null
    }

    private fun handleChannelList(
        text: String
    ) {

        val content =
            text.substringAfter(
                "$MSG_CHANNEL_LIST:",
                ""
            )

        val result =
            ArrayList<ChannelInfo>()

        if (
            content.isNotBlank()
        ) {

            for (
            entry in content.split(";")
            ) {

                if (
                    entry.isBlank()
                ) {

                    continue
                }

                val fields =
                    entry.trim()
                        .split(",")

                val name =
                    fields.getOrNull(0)
                        ?.trim()

                if (
                    name.isNullOrBlank()
                ) {

                    continue
                }

                val type =
                    fields.getOrNull(1)
                        ?.trim()
                        ?.uppercase()
                        ?: "PUBLIC"

                val count =
                    fields.getOrNull(2)
                        ?.trim()
                        ?.toIntOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0

                result.add(
                    ChannelInfo(
                        name =
                            name,
                        onlineCount =
                            count,
                        isPrivate =
                            type ==
                                    "PRIVATE"
                    )
                )
            }
        }

        if (
            !result.any {
                it.name ==
                        currentChannel
            }
        ) {

            result.add(
                ChannelInfo(
                    name =
                        currentChannel,
                    onlineCount =
                        currentChannelOnlineCount,
                    isPrivate =
                        currentChannelPrivate
                )
            )
        }

        cachedChannelInfoList =
            ArrayList(
                result
                    .distinctBy {
                        it.name
                    }
                    .sortedBy {
                        it.name
                    }
            )

        cachedChannelInfoList
            .firstOrNull {
                it.name ==
                        currentChannel
            }?.let {

                currentChannelOnlineCount =
                    it.onlineCount

                currentChannelPrivate =
                    it.isPrivate

                currentChannelRequirePassword =
                    it.isPrivate
            }

        broadcastChannelList()

        broadcastChannelStatus(
            "频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
        )
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
        channel in cachedChannelInfoList
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

        sendBroadcast(intent)
    }

    private fun createChannel(
        channel: String,
        password: String,
        privateChannel: Boolean
    ) {

        if (
            !isConnected
        ) {

            return
        }

        val name =
            channel.trim()

        val cleanPassword =
            password.trim()

        if (
            name.isBlank()
        ) {

            return
        }

        if (
            name.length > 24
        ) {

            broadcastChannelStatus(
                "频道名称不能超过24个字符"
            )

            return
        }

        if (
            name.contains(":") ||
            name.contains(",") ||
            name.contains(";") ||
            name.contains("|") ||
            name.contains("\n") ||
            name.contains("\r")
        ) {

            broadcastChannelStatus(
                "频道名称包含非法字符"
            )

            return
        }

        if (
            privateChannel &&
            cleanPassword.isBlank()
        ) {

            broadcastChannelStatus(
                "私密频道必须设置密码"
            )

            return
        }

        if (
            cleanPassword.length > 32
        ) {

            broadcastChannelStatus(
                "频道密码不能超过32个字符"
            )

            return
        }

        pendingCreateChannelName =
            name

        pendingCreateChannelPassword =
            cleanPassword

        channelSwitching =
            true

        val type =
            if (
                privateChannel
            ) {
                "PRIVATE"
            } else {
                "PUBLIC"
            }

        val message =
            if (
                privateChannel
            ) {

                "WALKIE_CREATE_CHANNEL:" +
                        name +
                        ":" +
                        type +
                        ":" +
                        cleanPassword

            } else {

                "WALKIE_CREATE_CHANNEL:" +
                        name +
                        ":" +
                        type
            }

        sendMessageAsync(
            message
        )
    }

    private fun joinChannel(
        channel: String,
        password: String
    ) {

        if (
            !isConnected
        ) {

            return
        }

        val name =
            channel.trim()

        val cleanPassword =
            password.trim()

        if (
            name.isBlank()
        ) {

            return
        }

        if (
            cleanPassword.isNotBlank()
        ) {

            reconnectChannelPassword =
                cleanPassword
        }

        if (
            name ==
            currentChannel
        ) {

            channelSwitching =
                false

            broadcastChannelStatus(
                "当前已经在频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
            )

            return
        }

        talkRequesting =
            false

        talkAllowed =
            false

        isSpeaking =
            false

        stopRecording()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        channelSwitching =
            true

        val message =
            if (
                cleanPassword.isBlank()
            ) {

                "WALKIE_JOIN_CHANNEL:$name"

            } else {

                "WALKIE_JOIN_CHANNEL:" +
                        name +
                        ":" +
                        cleanPassword
            }

        sendMessageAsync(
            message
        )
    }

    private fun handleChannelJoined(
        text: String
    ) {

        val content =
            text.substringAfter(
                "$MSG_CHANNEL_JOINED:",
                ""
            )

        val fields =
            content.split(":")

        val name =
            fields.getOrNull(0)
                ?.trim()
                ?: return

        if (
            name.isBlank()
        ) {

            return
        }

        val type =
            fields.getOrNull(1)
                ?.trim()
                ?.uppercase()
                ?: "PUBLIC"

        val count =
            fields.getOrNull(2)
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0

        currentChannel =
            name

        reconnectChannel =
            name

        currentChannelOnlineCount =
            count

        currentChannelPrivate =
            type ==
                    "PRIVATE"

        currentChannelRequirePassword =
            currentChannelPrivate

        if (
            !currentChannelPrivate &&
            name ==
            "public"
        ) {

            reconnectChannelPassword =
                ""
        }

        channelSwitching =
            false

        talkRequesting =
            false

        talkAllowed =
            false

        isSpeaking =
            false

        stopRecording()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        pendingCreateChannelName =
            ""

        pendingCreateChannelPassword =
            ""

        updateCurrentChannelInfo()

        broadcastChannelStatus(
            "已进入频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
        )

        requestChannelList()
    }

    private fun handleChannelCreated(
        text: String
    ) {

        val content =
            text.substringAfter(
                "$MSG_CHANNEL_CREATED:",
                ""
            )

        val fields =
            content.split(":")

        val name =
            fields.getOrNull(0)
                ?.trim()
                ?: return

        if (
            name.isBlank()
        ) {

            return
        }

        val privateChannel =
            fields.getOrNull(1)
                ?.trim()
                ?.uppercase() ==
                    "PRIVATE"

        currentChannelPrivate =
            privateChannel

        currentChannelRequirePassword =
            privateChannel

        joinChannel(
            name,
            pendingCreateChannelPassword
        )
    }

    private fun handleChannelDeleted(
        text: String
    ) {

        val deletedChannel =
            text.substringAfter(
                "$MSG_CHANNEL_DELETED:",
                ""
            ).trim()

        if (
            deletedChannel ==
            currentChannel
        ) {

            currentChannel =
                "public"

            reconnectChannel =
                "public"

            reconnectChannelPassword =
                ""

            currentChannelOnlineCount =
                0

            currentChannelPrivate =
                false

            currentChannelRequirePassword =
                false

            channelSwitching =
                false

            talkRequesting =
                false

            talkAllowed =
                false

            isSpeaking =
                false

            stopRecording()

            setTalkStatus(
                TALK_STATUS_RELEASED
            )

            broadcastChannelStatus(
                "频道已删除：$deletedChannel，已返回 public"
            )
        }

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

        sendBroadcast(intent)

        requestChannelList()
    }

    private fun handleChannelError(
        text: String
    ) {

        val error =
            text.substringAfter(
                "$MSG_CHANNEL_ERROR:",
                "UNKNOWN"
            ).trim()

        channelSwitching =
            false

        val message =
            when (
                error
            ) {

                "BAD_PASSWORD" ->
                    "频道密码错误"

                "PASSWORD_REQUIRED" ->
                    "该频道需要密码"

                "NOT_FOUND" ->
                    "频道不存在"

                "EXISTS" ->
                    "频道已经存在"

                "LIMIT" ->
                    "频道数量已达到上限"

                "NOT_CREATOR" ->
                    "只有频道创建者可以删除"

                "CANNOT_DELETE_PUBLIC" ->
                    "public 频道不能删除"

                "INVALID_NAME" ->
                    "频道名称无效"

                else ->
                    "频道操作失败：$error"
            }

        broadcastChannelStatus(
            message
        )

        requestChannelList()
    }

    private fun handleChannelLeft(
        text: String
    ) {

        val content =
            text.substringAfter(
                "$MSG_CHANNEL_LEFT:",
                "public"
            )

        val fields =
            content.split(":")

        currentChannel =
            fields.getOrNull(0)
                ?.trim()
                ?.ifBlank {
                    "public"
                }
                ?: "public"

        currentChannelOnlineCount =
            fields.getOrNull(1)
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0

        currentChannelPrivate =
            false

        currentChannelRequirePassword =
            false

        channelSwitching =
            false

        if (
            currentChannel ==
            "public"
        ) {

            reconnectChannel =
                "public"

            reconnectChannelPassword =
                ""
        }

        updateCurrentChannelInfo()

        broadcastChannelStatus(
            "当前频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
        )
    }

    private fun updateCurrentChannelInfo() {

        val list =
            cachedChannelInfoList.toMutableList()

        val index =
            list.indexOfFirst {
                it.name ==
                        currentChannel
            }

        val info =
            ChannelInfo(
                name =
                    currentChannel,
                onlineCount =
                    currentChannelOnlineCount,
                isPrivate =
                    currentChannelPrivate
            )

        if (
            index >= 0
        ) {

            list[index] =
                info

        } else {

            list.add(info)
        }

        cachedChannelInfoList =
            ArrayList(
                list.sortedBy {
                    it.name
                }
            )

        broadcastChannelList()
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

        sendBroadcast(intent)

        updateNotification()
    }

    private fun deleteChannel(
        channel: String
    ) {

        if (
            !isConnected
        ) {

            return
        }

        if (
            channel.isBlank()
        ) {

            return
        }

        if (
            channel ==
            "public"
        ) {

            broadcastChannelStatus(
                "public 频道不能删除"
            )

            return
        }

        sendMessageAsync(
            "WALKIE_DELETE_CHANNEL:$channel"
        )
    }

    /*
     * ============================================================
     * UDP发送
     * ============================================================
     */

    private fun sendMessageNow(
        message: String
    ) {

        val socket =
            udpSocket

        val address =
            serverAddress

        if (
            socket == null ||
            socket.isClosed ||
            address == null
        ) {

            return
        }

        try {

            val data =
                message.toByteArray(
                    Charsets.UTF_8
                )

            val packet =
                DatagramPacket(
                    data,
                    data.size,
                    address,
                    SERVER_PORT
                )

            socket.send(packet)

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: UDP发送失败=${e.message}"
            )
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

    /*
     * ============================================================
     * PTT
     * ============================================================
     */

    private fun requestTalk() {

        if (
            !isConnected ||
            channelSwitching
        ) {

            return
        }

        if (
            talkRequesting ||
            talkAllowed
        ) {

            return
        }

        talkRequesting =
            true

        talkAllowed =
            false

        setTalkStatus(
            TALK_STATUS_REQUESTING
        )

        sendMessageAsync(
            MSG_TALK_START
        )
    }

    private fun releaseTalk() {

        isSpeaking =
            false

        talkRequesting =
            false

        talkAllowed =
            false

        stopRecording()

        if (
            isConnected
        ) {

            sendMessageAsync(
                MSG_TALK_STOP
            )
        }

        setTalkStatus(
            TALK_STATUS_RELEASED
        )
    }

    /*
     * ============================================================
     * 录音
     * ============================================================
     */

    private fun startRecording() {

        if (
            recordJob?.isActive == true
        ) {

            return
        }

        if (
            !talkAllowed
        ) {

            return
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            println(
                "WALKIE $WALKIE_VERSION: 没有录音权限"
            )

            return
        }

        recordJob =
            serviceScope.launch {

                var recorder:
                        AudioRecord? =
                    null

                try {

                    val minBuffer =
                        AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )

                    if (
                        minBuffer <= 0
                    ) {

                        return@launch
                    }

                    val recordBuffer =
                        maxOf(
                            minBuffer * 2,
                            AUDIO_PACKET_SIZE * 4,
                            4096
                        )

                    recorder =
                        try {

                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                recordBuffer
                            )

                        } catch (e: Exception) {

                            println(
                                "WALKIE $WALKIE_VERSION: AudioRecord创建失败=${e.message}"
                            )

                            null
                        }

                    if (
                        recorder == null
                    ) {

                        return@launch
                    }

                    if (
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {
                            recorder.release()
                        } catch (_: Exception) {
                        }

                        recorder =
                            try {

                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    SAMPLE_RATE,
                                    AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    recordBuffer
                                )

                            } catch (_: Exception) {

                                null
                            }
                    }

                    if (
                        recorder == null ||
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {
                            recorder?.release()
                        } catch (_: Exception) {
                        }

                        recorder =
                            null

                        return@launch
                    }

                    audioRecord =
                        recorder

                    setupAudioEffects(
                        recorder.audioSessionId
                    )

                    val packetBuffer =
                        ByteArray(
                            AUDIO_PACKET_SIZE
                        )

                    val readBuffer =
                        ByteArray(
                            AUDIO_PACKET_SIZE
                        )

                    isSpeaking =
                        true

                    synchronized(
                        audioRecordLock
                    ) {

                        recorder.startRecording()
                    }

                    if (
                        recorder.recordingState !=
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {

                        return@launch
                    }

                    println(
                        "WALKIE $WALKIE_VERSION: ★开始录音★"
                    )

                    while (
                        serviceScope.isActive &&
                        isSpeaking &&
                        talkAllowed &&
                        isConnected &&
                        !shuttingDown
                    ) {

                        var filled =
                            0

                        while (
                            filled <
                            AUDIO_PACKET_SIZE &&
                            serviceScope.isActive &&
                            isSpeaking &&
                            talkAllowed &&
                            isConnected &&
                            !shuttingDown
                        ) {

                            val read =
                                try {

                                    synchronized(
                                        audioRecordLock
                                    ) {

                                        recorder.read(
                                            readBuffer,
                                            0,
                                            readBuffer.size,
                                            AudioRecord.READ_BLOCKING
                                        )
                                    }

                                } catch (e: Exception) {

                                    println(
                                        "WALKIE $WALKIE_VERSION: AudioRecord.read异常=${e.message}"
                                    )

                                    -1
                                }

                            if (
                                read > 0
                            ) {

                                val copySize =
                                    min(
                                        read,
                                        AUDIO_PACKET_SIZE -
                                                filled
                                    )

                                System.arraycopy(
                                    readBuffer,
                                    0,
                                    packetBuffer,
                                    filled,
                                    copySize
                                )

                                filled +=
                                    copySize

                            } else if (
                                read < 0
                            ) {

                                break
                            }
                        }

                        if (
                            filled !=
                            AUDIO_PACKET_SIZE
                        ) {

                            continue
                        }

                        val pcm =
                            ShortArray(
                                AUDIO_PACKET_SIZE / 2
                            )

                        var index =
                            0

                        while (
                            index <
                            pcm.size
                        ) {

                            val low =
                                packetBuffer[
                                    index * 2
                                ].toInt() and
                                        0xff

                            val high =
                                packetBuffer[
                                    index * 2 + 1
                                ].toInt()

                            pcm[index] =
                                (
                                        (high shl 8) or
                                                low
                                        ).toShort()

                            index++
                        }

                        val encoder =
                            opusEncoder
                                ?: continue

                        val opus =
                            try {

                                encoder.encode(
                                    pcm
                                )

                            } catch (e: Throwable) {

                                println(
                                    "WALKIE $WALKIE_VERSION: Opus编码异常=${e.message}"
                                )

                                null
                            }

                        if (
                            opus == null ||
                            opus.isEmpty()
                        ) {

                            continue
                        }

                        if (
                            opus.size >
                            MAX_OPUS_PACKET_SIZE
                        ) {

                            continue
                        }

                        val socket =
                            udpSocket

                        val address =
                            serverAddress

                        if (
                            socket == null ||
                            socket.isClosed ||
                            address == null
                        ) {

                            break
                        }

                        try {

                            val packet =
                                DatagramPacket(
                                    opus,
                                    opus.size,
                                    address,
                                    SERVER_PORT
                                )

                            socket.send(packet)

                        } catch (e: Exception) {

                            println(
                                "WALKIE $WALKIE_VERSION: OPUS发送失败=${e.message}"
                            )

                            break
                        }
                    }

                } catch (
                    e: Throwable
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: 录音线程异常=${e.message}"
                    )

                } finally {

                    isSpeaking =
                        false

                    releaseAudioEffects()

                    if (
                        recorder != null
                    ) {

                        try {

                            synchronized(
                                audioRecordLock
                            ) {

                                if (
                                    recorder.recordingState ==
                                    AudioRecord.RECORDSTATE_RECORDING
                                ) {

                                    recorder.stop()
                                }
                            }

                        } catch (_: Exception) {
                        }

                        try {
                            recorder.release()
                        } catch (_: Exception) {
                        }
                    }

                    if (
                        audioRecord ===
                        recorder
                    ) {

                        audioRecord =
                            null
                    }

                    println(
                        "WALKIE $WALKIE_VERSION: 录音结束"
                    )
                }
            }
    }

    /*
     * ============================================================
     * Audio Effects
     * ============================================================
     */

    private fun setupAudioEffects(
        audioSessionId: Int
    ) {

        releaseAudioEffects()

        try {

            if (
                NoiseSuppressor.isAvailable()
            ) {

                noiseSuppressor =
                    NoiseSuppressor.create(
                        audioSessionId
                    )

                noiseSuppressor?.enabled =
                    true
            }

        } catch (_: Exception) {
        }

        try {

            if (
                AutomaticGainControl.isAvailable()
            ) {

                automaticGainControl =
                    AutomaticGainControl.create(
                        audioSessionId
                    )

                automaticGainControl?.enabled =
                    true
            }

        } catch (_: Exception) {
        }

        try {

            if (
                AcousticEchoCanceler.isAvailable()
            ) {

                acousticEchoCanceler =
                    AcousticEchoCanceler.create(
                        audioSessionId
                    )

                acousticEchoCanceler?.enabled =
                    true
            }

        } catch (_: Exception) {
        }
    }

    private fun releaseAudioEffects() {

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        try {
            automaticGainControl?.release()
        } catch (_: Exception) {
        }

        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {
        }

        noiseSuppressor =
            null

        automaticGainControl =
            null

        acousticEchoCanceler =
            null
    }

    private fun stopRecording() {

        isSpeaking =
            false

        recordJob?.cancel()

        recordJob =
            null

        val recorder =
            audioRecord

        if (
            recorder != null
        ) {

            try {

                synchronized(
                    audioRecordLock
                ) {

                    if (
                        recorder.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {

                        recorder.stop()
                    }
                }

            } catch (_: Exception) {
            }

            try {
                recorder.release()
            } catch (_: Exception) {
            }

            if (
                audioRecord ===
                recorder
            ) {

                audioRecord =
                    null
            }
        }

        releaseAudioEffects()
    }

    /*
     * ============================================================
     * 播放队列
     * ============================================================
     */

    private fun enqueueAudio(
        data: ByteArray
    ) {

        if (
            data.isEmpty() ||
            data.size % 2 != 0
        ) {

            return
        }

        if (
            data.size > 8192
        ) {

            return
        }

        /*
         * 队列太深时删最老的。
         */
        while (
            playbackQueue.size >=
            PLAYBACK_MAX_QUEUE_PACKETS
        ) {

            if (
                playbackQueue.poll() ==
                null
            ) {

                break
            }
        }

        if (
            !playbackQueue.offer(
                data
            )
        ) {

            playbackQueue.poll()

            playbackQueue.offer(
                data
            )
        }

        /*
         * 第一帧进入队列就确保播放线程存在。
         *
         * V19 不再等收到5包之后才启动播放线程。
         */
        startPlaybackWorker()
    }

    private fun applyPlaybackGain(
        input: ByteArray
    ): ByteArray {

        if (
            PLAYBACK_GAIN <=
            1.0f
        ) {

            return input
        }

        if (
            input.isEmpty() ||
            input.size % 2 != 0
        ) {

            return input
        }

        val output =
            ByteArray(
                input.size
            )

        var index =
            0

        while (
            index <
            input.size
        ) {

            val low =
                input[index]
                    .toInt() and
                        0xff

            val high =
                input[index + 1]
                    .toInt()

            var sample =
                (high shl 8) or
                        low

            if (
                sample >
                32767
            ) {

                sample -=
                    65536
            }

            val amplified =
                (
                        sample.toFloat() *
                                PLAYBACK_GAIN
                        )
                    .toInt()
                    .coerceIn(
                        -32768,
                        32767
                    )

            output[index] =
                (
                        amplified and
                                0xff
                        ).toByte()

            output[index + 1] =
                (
                        (amplified shr 8) and
                                0xff
                        ).toByte()

            index +=
                2
        }

        return output
    }

    /*
     * ============================================================
     * 将没有成功播放的帧重新放到队列最前面
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

        val existing =
            ArrayList<ByteArray>()

        while (true) {

            val item =
                playbackQueue.poll()
                    ?: break

            existing.add(
                item
            )
        }

        val merged =
            ArrayList<ByteArray>(
                frames.size +
                        existing.size
            )

        /*
         * 当前尚未播放的帧必须最前。
         */
        merged.addAll(
            frames
        )

        merged.addAll(
            existing
        )

        /*
         * 不超过整个队列容量。
         */
        val maxItems =
            min(
                merged.size,
                PLAYBACK_QUEUE_CAPACITY
            )

        var index =
            0

        while (
            index <
            maxItems
        ) {

            playbackQueue.offer(
                merged[index]
            )

            index++
        }
    }

    /*
     * ============================================================
     * 唯一播放线程
     * ============================================================
     */

    private fun startPlaybackWorker() {

        synchronized(
            playbackWorkerLock
        ) {

            if (
                playbackJob?.isActive == true
            ) {

                return
            }

            if (
                playbackWorkerStarting
            ) {

                return
            }

            playbackWorkerStarting =
                true

            playbackJob =
                serviceScope.launch {

                    try {

                        playbackLoop()

                    } finally {

                        synchronized(
                            playbackWorkerLock
                        ) {

                            playbackWorkerStarting =
                                false
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

        /*
         * 第一次进入播放时需要最小缓冲。
         */
        var firstStart =
            true

        while (
            serviceScope.isActive &&
            !shuttingDown
        ) {

            /*
             * ----------------------------------------------------
             * V19 起播策略
             * ----------------------------------------------------
             *
             * 第一次只等待2包。
             *
             * 2 * 20ms = 40ms。
             *
             * 尽量减少讲话开头的等待时间。
             */
            val recovering =
                playbackRecoveryRequested

            val requiredPackets =
                if (
                    firstStart
                ) {

                    PLAYBACK_START_BUFFER_PACKETS

                } else if (
                    recovering
                ) {

                    PLAYBACK_RECOVERY_BUFFER_PACKETS

                } else {

                    1
                }

            while (
                serviceScope.isActive &&
                !shuttingDown &&
                playbackQueue.size <
                requiredPackets
            ) {

                delay(4L)
            }

            if (
                !serviceScope.isActive ||
                shuttingDown
            ) {

                break
            }

            ensureAudioPlayer()

            val track =
                synchronized(
                    audioTrackLock
                ) {

                    audioTrack
                }

            if (
                track == null ||
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                delay(25L)

                continue
            }

            /*
             * ----------------------------------------------------
             * 起播 / UNDERRUN恢复
             * ----------------------------------------------------
             */

            if (
                firstStart ||
                recovering ||
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                val frames =
                    ArrayList<ByteArray>()

                repeat(
                    requiredPackets
                ) {

                    val frame =
                        playbackQueue.poll()

                    if (
                        frame != null
                    ) {

                        frames.add(
                            frame
                        )
                    }
                }

                if (
                    frames.isEmpty()
                ) {

                    continue
                }

                val combined =
                    combineFrames(
                        frames
                    )

                var writeSuccess =
                    false

                synchronized(
                    audioTrackLock
                ) {

                    val current =
                        audioTrack

                    if (
                        current == null ||
                        current !== track ||
                        current.state !=
                        AudioTrack.STATE_INITIALIZED
                    ) {

                        writeSuccess =
                            false

                    } else {

                        try {

                            /*
                             * 保留已经验证正常的扬声器输出。
                             */
                            setTrackSpeaker(
                                current
                            )

                            val result =
                                current.write(
                                    applyPlaybackGain(
                                        combined
                                    ),
                                    0,
                                    combined.size,
                                    AudioTrack.WRITE_BLOCKING
                                )

                            if (
                                result > 0
                            ) {

                                current.play()

                                writeSuccess =
                                    true

                                println(
                                    "WALKIE AUDIO: ★V19起播★ packets=${frames.size} queue=${playbackQueue.size}"
                                )
                            }

                        } catch (
                            e: Throwable
                        ) {

                            println(
                                "WALKIE AUDIO: 起播/恢复异常=${e.message}"
                            )

                            writeSuccess =
                                false
                        }
                    }
                }

                if (
                    !writeSuccess
                ) {

                    /*
                     * 不能直接丢掉讲话最前面的数据。
                     */
                    requeueFront(
                        frames
                    )

                    handlePlaybackFailure(
                        track
                    )

                    playbackRecoveryRequested =
                        true

                    delay(30L)

                    continue
                }

                playbackRecoveryRequested =
                    false

                firstStart =
                    false

                lastUnderrunCount =
                    getUnderrunCount(
                        track
                    )

                continue
            }

            /*
             * ----------------------------------------------------
             * 正常播放
             * ----------------------------------------------------
             *
             * 一次取1~2帧。
             *
             * 这样既不会让延迟越来越大，
             * 也不会每20ms都频繁进入AudioTrack。
             */
            val first =
                try {

                    playbackQueue.poll(
                        40L,
                        TimeUnit.MILLISECONDS
                    )

                } catch (
                    _: InterruptedException
                ) {

                    null
                }

            if (
                first == null
            ) {

                continue
            }

            val second =
                playbackQueue.poll()

            val frames =
                if (
                    second != null
                ) {

                    listOf(
                        first,
                        second
                    )

                } else {

                    listOf(
                        first
                    )
                }

            val combined =
                combineFrames(
                    frames
                )

            var failed =
                false

            synchronized(
                audioTrackLock
            ) {

                val current =
                    audioTrack

                if (
                    current == null ||
                    current !== track ||
                    current.state !=
                    AudioTrack.STATE_INITIALIZED
                ) {

                    failed =
                        true

                } else {

                    try {

                        if (
                            current.playState !=
                            AudioTrack.PLAYSTATE_PLAYING
                        ) {

                            /*
                             * 系统暂停后：
                             * 当前帧先写进去，
                             * 成功以后再play。
                             */
                            val result =
                                current.write(
                                    applyPlaybackGain(
                                        combined
                                    ),
                                    0,
                                    combined.size,
                                    AudioTrack.WRITE_BLOCKING
                                )

                            if (
                                result > 0
                            ) {

                                setTrackSpeaker(
                                    current
                                )

                                current.play()

                                println(
                                    "WALKIE AUDIO: ★AudioTrack恢复播放★ queue=${playbackQueue.size}"
                                )

                            } else {

                                failed =
                                    true
                            }

                        } else {

                            val result =
                                current.write(
                                    applyPlaybackGain(
                                        combined
                                    ),
                                    0,
                                    combined.size,
                                    AudioTrack.WRITE_BLOCKING
                                )

                            if (
                                result <= 0
                            ) {

                                println(
                                    "WALKIE AUDIO: AudioTrack.write失败=$result"
                                )

                                failed =
                                    true
                            }
                        }

                    } catch (
                        e: Throwable
                    ) {

                        println(
                            "WALKIE AUDIO: 正常播放异常=${e.message}"
                        )

                        failed =
                            true
                    }
                }
            }

            if (
                failed
            ) {

                /*
                 * 当前没有播放成功的数据不能丢。
                 */
                requeueFront(
                    frames
                )

                handlePlaybackFailure(
                    track
                )

                playbackRecoveryRequested =
                    true

                delay(25L)

                continue
            }

            /*
             * ----------------------------------------------------
             * underrun监控
             * ----------------------------------------------------
             */

            val currentUnderrun =
                getUnderrunCount(
                    track
                )

            if (
                currentUnderrun >
                lastUnderrunCount
            ) {

                val delta =
                    currentUnderrun -
                            lastUnderrunCount

                println(
                    "WALKIE AUDIO: ★underrun +$delta total=$currentUnderrun queue=${playbackQueue.size}★"
                )

                lastUnderrunCount =
                    currentUnderrun

                /*
                 * UNDERRUN不马上销毁AudioTrack。
                 *
                 * 下一轮等待3包，
                 * 恢复后继续。
                 */
                playbackRecoveryRequested =
                    true
            }
        }
    }

    /*
     * ============================================================
     * 合并PCM
     * ============================================================
     */

    private fun combineFrames(
        frames: List<ByteArray>
    ): ByteArray {

        if (
            frames.isEmpty()
        ) {

            return ByteArray(0)
        }

        if (
            frames.size == 1
        ) {

            return frames[0]
        }

        var total =
            0

        for (
        frame in frames
        ) {

            total +=
                frame.size
        }

        val combined =
            ByteArray(total)

        var offset =
            0

        for (
        frame in frames
        ) {

            System.arraycopy(
                frame,
                0,
                combined,
                offset,
                frame.size
            )

            offset +=
                frame.size
        }

        return combined
    }

    private fun getUnderrunCount(
        track: AudioTrack?
    ): Int {

        if (
            track == null
        ) {

            return 0
        }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.N
        ) {

            return 0
        }

        return try {

            track.underrunCount

        } catch (_: Exception) {

            0
        }
    }

    /*
     * ============================================================
     * AudioTrack异常处理
     * ============================================================
     */

    private fun handlePlaybackFailure(
        track: AudioTrack
    ) {

        synchronized(
            audioTrackLock
        ) {

            if (
                audioTrack !==
                track
            ) {

                return
            }

            val state =
                try {

                    track.state

                } catch (_: Exception) {

                    AudioTrack.STATE_UNINITIALIZED
                }

            if (
                state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                audioTrack =
                    null

                try {
                    track.release()
                } catch (_: Exception) {
                }

                println(
                    "WALKIE AUDIO: AudioTrack已失效，准备重建"
                )

            } else {

                /*
                 * Track本身还有效，
                 * 尝试重新播放，不马上销毁。
                 */
                try {

                    if (
                        track.playState !=
                        AudioTrack.PLAYSTATE_PLAYING
                    ) {

                        track.play()
                    }

                } catch (_: Exception) {
                }
            }
        }
    }

    /*
     * ============================================================
     * AudioTrack
     * ============================================================
     */

    private fun ensureAudioPlayer() {

        val current =
            synchronized(
                audioTrackLock
            ) {

                audioTrack
            }

        if (
            current != null &&
            current.state ==
            AudioTrack.STATE_INITIALIZED
        ) {

            return
        }

        createAudioPlayer()
    }

    private fun createAudioPlayer() {

        synchronized(
            audioTrackLock
        ) {

            val oldTrack =
                audioTrack

            if (
                oldTrack != null &&
                oldTrack.state ==
                AudioTrack.STATE_INITIALIZED
            ) {

                return
            }

            audioTrack =
                null

            try {
                oldTrack?.release()
            } catch (_: Exception) {
            }

            val channelConfig =
                AudioFormat.CHANNEL_OUT_MONO

            val encoding =
                AudioFormat.ENCODING_PCM_16BIT

            val minBuffer =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    channelConfig,
                    encoding
                )

            if (
                minBuffer <= 0
            ) {

                println(
                    "WALKIE AUDIO: getMinBufferSize失败=$minBuffer"
                )

                return
            }

            /*
             * 继续使用当前已验证过能正常出声的buffer策略。
             */
            val bufferSize =
                maxOf(
                    minBuffer * 4,
                    AUDIO_PACKET_SIZE * 16
                )

            val attributes =
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()

            val format =
                AudioFormat.Builder()
                    .setSampleRate(
                        SAMPLE_RATE
                    )
                    .setEncoding(
                        encoding
                    )
                    .setChannelMask(
                        channelConfig
                    )
                    .build()

            val track =
                try {

                    AudioTrack.Builder()
                        .setAudioAttributes(
                            attributes
                        )
                        .setAudioFormat(
                            format
                        )
                        .setBufferSizeInBytes(
                            bufferSize
                        )
                        .setTransferMode(
                            AudioTrack.MODE_STREAM
                        )
                        .build()

                } catch (e: Exception) {

                    println(
                        "WALKIE AUDIO: AudioTrack创建失败=${e.message}"
                    )

                    return
                }

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                println(
                    "WALKIE AUDIO: AudioTrack状态异常=${track.state}"
                )

                try {
                    track.release()
                } catch (_: Exception) {
                }

                return
            }

            /*
             * 关键：
             *
             * 继续保留已经验证有声音的
             * setPreferredDevice(内置扬声器)。
             */
            setTrackSpeaker(
                track
            )

            try {

                track.setVolume(
                    1.0f
                )

            } catch (_: Exception) {
            }

            audioTrack =
                track

            lastUnderrunCount =
                getUnderrunCount(
                    track
                )

            println(
                "WALKIE AUDIO: AudioTrack创建成功 buffer=$bufferSize speaker=${findBuiltInSpeaker() != null}"
            )
        }
    }

    private fun setTrackSpeaker(
        track: AudioTrack
    ) {

        if (
            track.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            return
        }

        val speaker =
            findBuiltInSpeaker()

        if (
            speaker == null
        ) {

            return
        }

        try {

            val result =
                track.setPreferredDevice(
                    speaker
                )

            if (
                result
            ) {

                println(
                    "WALKIE AUDIO: 首选输出=内置扬声器"
                )
            }

        } catch (e: Exception) {

            println(
                "WALKIE AUDIO: 设置AudioTrack扬声器失败=${e.message}"
            )
        }
    }

    /*
     * ============================================================
     * 释放播放
     * ============================================================
     */

    private fun releaseAudioPlayer() {

        synchronized(
            playbackWorkerLock
        ) {

            playbackJob?.cancel()

            playbackJob =
                null

            playbackWorkerStarting =
                false
        }

        playbackQueue.clear()

        playbackRecoveryRequested =
            false

        synchronized(
            audioTrackLock
        ) {

            val track =
                audioTrack

            audioTrack =
                null

            if (
                track != null
            ) {

                try {
                    track.pause()
                } catch (_: Exception) {
                }

                try {
                    track.flush()
                } catch (_: Exception) {
                }

                try {
                    track.stop()
                } catch (_: Exception) {
                }

                try {
                    track.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /*
     * ============================================================
     * 状态
     * ============================================================
     */

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

            stopChannelRefreshWorker()
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

        sendBroadcast(intent)

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

        sendBroadcast(intent)
    }

    /*
     * ============================================================
     * 清理
     * ============================================================
     */

    private fun cleanupSocket(
        socket: DatagramSocket
    ) {

        try {
            socket.close()
        } catch (_: Exception) {
        }

        if (
            udpSocket ===
            socket
        ) {

            udpSocket =
                null
        }
    }

    private fun cleanupConnection() {

        if (
            currentChannel.isNotBlank() &&
            currentChannel !=
            "public"
        ) {

            reconnectChannel =
                currentChannel
        }

        talkRequesting =
            false

        talkAllowed =
            false

        isSpeaking =
            false

        stopRecording()

        closeSocket()

        serverAddress =
            null

        /*
         * 重连时不播放旧语音。
         */
        playbackQueue.clear()

        playbackRecoveryRequested =
            true

        currentChannel =
            "public"

        currentChannelOnlineCount =
            0

        currentChannelPrivate =
            false

        currentChannelRequirePassword =
            false

        channelSwitching =
            false

        cachedChannelInfoList =
            ArrayList()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        setConnected(
            false
        )
    }

    private fun closeSocket() {

        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }

        udpSocket =
            null
    }

    private fun stopAll() {

        shuttingDown =
            true

        try {

            if (
                isConnected
            ) {

                if (
                    talkAllowed ||
                    talkRequesting
                ) {

                    sendMessageNow(
                        MSG_TALK_STOP
                    )
                }

                sendMessageNow(
                    MSG_GOODBYE
                )
            }

        } catch (_: Exception) {
        }

        networkJob?.cancel()

        networkJob =
            null

        stopChannelRefreshWorker()

        stopBackgroundDiagnostic()

        stopRecording()

        closeSocket()

        releaseAudioPlayer()

        talkRequesting =
            false

        talkAllowed =
            false

        isSpeaking =
            false

        isConnected =
            false

        serverIp =
            null

        serverAddress =
            null

        currentChannel =
            "public"

        currentChannelOnlineCount =
            0

        currentChannelPrivate =
            false

        currentChannelRequirePassword =
            false

        reconnectChannel =
            ""

        reconnectChannelPassword =
            ""

        channelSwitching =
            false

        cachedChannelInfoList =
            ArrayList()

        pendingCreateChannelName =
            ""

        pendingCreateChannelPassword =
            ""

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

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

        sendBroadcast(intent)

        updateNotification()
    }

    /*
     * ============================================================
     * WakeLock
     * ============================================================
     */

    private fun acquireWakeLock() {

        try {

            val powerManager =
                getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Walkie::KeepAlive"
                )

            wakeLock?.setReferenceCounted(
                false
            )

            if (
                wakeLock?.isHeld == false
            ) {

                wakeLock?.acquire()
            }

            println(
                "WALKIE $WALKIE_VERSION: WakeLock已开启"
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: WakeLock失败=${e.message}"
            )
        }
    }

    /*
     * ============================================================
     * Notification
     * ============================================================
     */

    private fun createNotification():
            Notification {

        val text =
            if (
                isConnected
            ) {

                "频道：$currentChannel  👥 ${currentChannelOnlineCount}人"

            } else {

                "正在连接服务器"
            }

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "WALKIE V20"
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setOngoing(
                true
            )
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "WALKIE 对讲服务",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "保持 WALKIE 后台运行"

            try {

                getSystemService(
                    NotificationManager::class.java
                ).createNotificationChannel(
                    channel
                )

            } catch (_: Exception) {
            }
        }
    }

    private fun updateNotification() {

        try {

            getSystemService(
                NotificationManager::class.java
            ).notify(
                NOTIFICATION_ID,
                createNotification()
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * Bind / Destroy
     * ============================================================
     */

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

        try {

            if (
                wakeLock?.isHeld == true
            ) {

                wakeLock?.release()
            }

        } catch (_: Exception) {
        }

        wakeLock =
            null

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val audioManager =
                    getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                audioManager.clearCommunicationDevice()
            }

        } catch (_: Exception) {
        }

        serviceScope.cancel()

        super.onDestroy()
    }
}