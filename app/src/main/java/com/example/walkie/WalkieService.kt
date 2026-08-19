package com.example.walkie

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.os.Build
import android.os.IBinder
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class WalkieService : Service() {

    companion object {

        private const val WALKIE_VERSION = "V11"

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

        private const val AUDIO_PACKET_SIZE =
            960

        private const val KEEP_ALIVE_INTERVAL =
            5000L

        private const val SOCKET_RECEIVE_TIMEOUT =
            1000

        private const val SERVER_ACTIVITY_TIMEOUT =
            60000L

        private const val INITIAL_RECONNECT_INTERVAL =
            1000L

        private const val MAX_RECONNECT_INTERVAL =
            5000L

        private const val PLAYBACK_QUEUE_CAPACITY =
            10

        private const val PLAYBACK_START_BUFFER_PACKETS =
            2

        private const val PLAYBACK_GAIN =
            1.50f

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

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var serverAddress: InetAddress? = null

    @Volatile
    private var serverIp: String? = null

    private var networkJob: Job? = null
    private var recordJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var noiseSuppressor: NoiseSuppressor? = null

    private var automaticGainControl:
            AutomaticGainControl? = null

    private var acousticEchoCanceler:
            AcousticEchoCanceler? = null

    private val playbackQueue =
        ArrayBlockingQueue<ByteArray>(
            PLAYBACK_QUEUE_CAPACITY
        )

    @Volatile
    private var isConnected = false

    @Volatile
    private var talkRequesting = false

    @Volatile
    private var talkAllowed = false

    @Volatile
    private var isSpeaking = false

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var lastKeepAliveTime = 0L

    @Volatile
    private var lastServerActivityTime = 0L

    @Volatile
    private var currentChannel = "public"

    @Volatile
    private var currentChannelOnlineCount = 0

    @Volatile
    private var currentChannelPrivate = false

    @Volatile
    private var currentChannelRequirePassword = false

    @Volatile
    private var channelSwitching = false

    @Volatile
    private var cachedChannelInfoList =
        ArrayList<ChannelInfo>()

    private var pendingCreateChannelName = ""

    private var pendingCreateChannelPassword = ""

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        setupSpeakerOutput()

        println(
            "WALKIE $WALKIE_VERSION: Service started"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                shuttingDown = false

                val ip =
                    intent.getStringExtra(
                        EXTRA_SERVER_IP
                    )

                if (!ip.isNullOrBlank()) {

                    startConnection(
                        ip.trim()
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

                if (!channel.isNullOrBlank()) {

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

                if (!channel.isNullOrBlank()) {

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

                if (!channel.isNullOrBlank()) {

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
     * 请求频道列表
     * ============================================================
     */

    private fun requestChannelList() {

        if (!isConnected) {
            return
        }

        println(
            "WALKIE $WALKIE_VERSION: ★请求频道列表★"
        )

        sendMessageAsync(
            MSG_CHANNEL_LIST
        )
    }

    /*
     * ============================================================
     * 删除频道
     * ============================================================
     */

    private fun deleteChannel(
        channel: String
    ) {

        if (!isConnected) {
            return
        }

        if (channel.isBlank()) {
            return
        }

        if (
            channel == "public"
        ) {

            broadcastChannelStatus(
                "public 频道不能删除"
            )

            return
        }

        println(
            "WALKIE $WALKIE_VERSION: ★发送删除频道=$channel★"
        )

        sendMessageAsync(
            "WALKIE_DELETE_CHANNEL:$channel"
        )
    }

    /*
     * ============================================================
     * 连接
     * ============================================================
     */

    private fun startConnection(
        ip: String
    ) {

        serverIp = ip

        if (
            networkJob?.isActive == true
        ) {
            return
        }

        println(
            "WALKIE $WALKIE_VERSION: 开始连接 $ip:$SERVER_PORT"
        )

        networkJob =
            serviceScope.launch {

                var reconnectDelay =
                    INITIAL_RECONNECT_INTERVAL

                while (
                    isActive &&
                    !shuttingDown
                ) {

                    try {

                        connectOnce(ip)

                        reconnectDelay =
                            INITIAL_RECONNECT_INTERVAL

                    } catch (e: Exception) {

                        println(
                            "WALKIE $WALKIE_VERSION: 网络异常=${e.message}"
                        )
                    }

                    if (
                        !isActive ||
                        shuttingDown
                    ) {
                        break
                    }

                    cleanupConnection()

                    delay(
                        reconnectDelay
                    )

                    reconnectDelay =
                        min(
                            reconnectDelay * 2,
                            MAX_RECONNECT_INTERVAL
                        )
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
            InetAddress.getByName(
                ip
            )

        serverAddress =
            address

        val socket =
            DatagramSocket()

        socket.soTimeout =
            SOCKET_RECEIVE_TIMEOUT

        try {

            socket.receiveBufferSize =
                64 * 1024

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

            setupSpeakerOutput()

            createAudioPlayer()

            sendMessageNow(
                MSG_HELLO
            )

            val now =
                System.currentTimeMillis()

            lastKeepAliveTime =
                now

            lastServerActivityTime =
                now

            val buffer =
                ByteArray(
                    4096
                )

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

                    lastKeepAliveTime =
                        currentTime
                }

                if (
                    isConnected &&
                    currentTime -
                    lastServerActivityTime >
                    SERVER_ACTIVITY_TIMEOUT
                ) {

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

                    socket.receive(
                        packet
                    )

                    val length =
                        packet.length

                    if (length <= 0) {
                        continue
                    }

                    lastServerActivityTime =
                        System.currentTimeMillis()

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

                        if (!isConnected) {

                            setConnected(
                                true
                            )

                            requestChannelList()
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

                        handleChannelList(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_JOINED:"
                        )
                    ) {

                        handleChannelJoined(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_CREATED:"
                        )
                    ) {

                        handleChannelCreated(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_DELETED:"
                        )
                    ) {

                        handleChannelDeleted(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_ERROR:"
                        )
                    ) {

                        handleChannelError(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_LEFT:"
                        )
                    ) {

                        handleChannelLeft(
                            text
                        )

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
                     * PCM
                     */

                    val audioData =
                        ByteArray(
                            length
                        )

                    System.arraycopy(
                        packet.data,
                        packet.offset,
                        audioData,
                        0,
                        length
                    )

                    enqueueAudio(
                        audioData
                    )

                } catch (
                    _: SocketTimeoutException
                ) {

                    // 正常轮询

                } catch (
                    e: SocketException
                ) {

                    if (!shuttingDown) {

                        println(
                            "WALKIE $WALKIE_VERSION: Socket=${e.message}"
                        )
                    }

                    throw e
                }
            }

        } finally {

            cleanupSocket(
                socket
            )
        }
    }

    /*
     * ============================================================
     * 频道列表
     * ============================================================
     */

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

        if (content.isNotBlank()) {

            val entries =
                content.split(";")

            for (entry in entries) {

                val fields =
                    entry.trim().split(",")

                if (fields.isEmpty()) {
                    continue
                }

                val name =
                    fields.getOrNull(0)
                        ?.trim()
                        ?: continue

                if (name.isBlank()) {
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

        broadcastChannelList()

        println(
            "WALKIE $WALKIE_VERSION: 频道列表=$cachedChannelInfoList"
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

    /*
     * ============================================================
     * 创建频道
     * ============================================================
     */

    private fun createChannel(
        channel: String,
        password: String,
        privateChannel: Boolean
    ) {

        if (!isConnected) {
            return
        }

        val name =
            channel.trim()

        val cleanPassword =
            password.trim()

        if (name.isBlank()) {
            return
        }

        if (name.length > 24) {

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

        if (cleanPassword.length > 32) {

            broadcastChannelStatus(
                "频道密码不能超过32个字符"
            )

            return
        }

        pendingCreateChannelName =
            name

        pendingCreateChannelPassword =
            cleanPassword

        channelSwitching = true

        val type =
            if (privateChannel) {
                "PRIVATE"
            } else {
                "PUBLIC"
            }

        val message =
            if (privateChannel) {

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

    /*
     * ============================================================
     * 加入频道
     * ============================================================
     */

    private fun joinChannel(
        channel: String,
        password: String
    ) {

        if (!isConnected) {
            return
        }

        val name =
            channel.trim()

        val cleanPassword =
            password.trim()

        if (name.isBlank()) {
            return
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

        talkRequesting = false
        talkAllowed = false
        isSpeaking = false

        stopRecording()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        channelSwitching = true

        val message =
            if (cleanPassword.isBlank()) {

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

    /*
     * ============================================================
     * 加入成功
     * ============================================================
     */

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

        if (name.isBlank()) {
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

        currentChannelOnlineCount =
            count

        currentChannelPrivate =
            type == "PRIVATE"

        currentChannelRequirePassword =
            currentChannelPrivate

        channelSwitching = false

        talkRequesting = false
        talkAllowed = false
        isSpeaking = false

        stopRecording()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        pendingCreateChannelName = ""
        pendingCreateChannelPassword = ""

        updateCurrentChannelInfo()

        broadcastChannelStatus(
            "已进入频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
        )

        requestChannelList()
    }

    /*
     * ============================================================
     * 创建成功
     * ============================================================
     */

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

        if (name.isBlank()) {
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

    /*
     * ============================================================
     * 删除成功
     *
     * VPS：
     * WALKIE_CHANNEL_DELETED:频道名
     * ============================================================
     */

    private fun handleChannelDeleted(
        text: String
    ) {

        val deletedChannel =
            text.substringAfter(
                "$MSG_CHANNEL_DELETED:",
                ""
            ).trim()

        println(
            "WALKIE $WALKIE_VERSION: ★频道删除成功=$deletedChannel★"
        )

        if (
            deletedChannel ==
            currentChannel
        ) {

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

        sendBroadcast(
            intent
        )

        requestChannelList()
    }

    /*
     * ============================================================
     * 错误
     * ============================================================
     */

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
            when (error) {

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

        println(
            "WALKIE $WALKIE_VERSION: 频道错误=$error"
        )
    }

    /*
     * ============================================================
     * 离开频道
     * ============================================================
     */

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

        if (index >= 0) {

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

        sendBroadcast(
            intent
        )

        updateNotification()
    }

    /*
     * ============================================================
     * UDP 发送
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

            socket.send(
                packet
            )

            println(
                "WALKIE $WALKIE_VERSION: UDP发送=$message"
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: UDP发送失败=${e.message}"
            )
        }
    }

    private fun sendMessageAsync(
        message: String
    ) {

        serviceScope.launch {
            sendMessageNow(
                message
            )
        }
    }

    /*
     * ============================================================
     * 抢麦
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

        println(
            "WALKIE $WALKIE_VERSION: ★抢麦★ channel=$currentChannel"
        )
    }

    private fun releaseTalk() {

        isSpeaking = false
        talkRequesting = false
        talkAllowed = false

        stopRecording()

        if (isConnected) {

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

        if (!talkAllowed) {
            return
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        setupSpeakerOutput()

        recordJob =
            serviceScope.launch {

                var recorder:
                        AudioRecord? = null

                try {

                    val channelConfig =
                        AudioFormat.CHANNEL_IN_MONO

                    val encoding =
                        AudioFormat.ENCODING_PCM_16BIT

                    val minBuffer =
                        AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            channelConfig,
                            encoding
                        )

                    if (minBuffer <= 0) {
                        return@launch
                    }

                    val recordBuffer =
                        max(
                            minBuffer * 2,
                            max(
                                AUDIO_PACKET_SIZE * 4,
                                4096
                            )
                        )

                    recorder =
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            SAMPLE_RATE,
                            channelConfig,
                            encoding,
                            recordBuffer
                        )

                    if (
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {
                            recorder.release()
                        } catch (_: Exception) {
                        }

                        recorder =
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE,
                                channelConfig,
                                encoding,
                                recordBuffer
                            )
                    }

                    if (
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {
                            recorder.release()
                        } catch (_: Exception) {
                        }

                        recorder = null

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

                    recorder.startRecording()

                    if (
                        recorder.recordingState !=
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        return@launch
                    }

                    while (
                        isActive &&
                        isSpeaking &&
                        talkAllowed &&
                        isConnected
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
                            break
                        }

                        var filled =
                            0

                        while (
                            filled <
                            AUDIO_PACKET_SIZE &&
                            isActive &&
                            isSpeaking &&
                            talkAllowed &&
                            isConnected
                        ) {

                            val read =
                                try {

                                    recorder.read(
                                        readBuffer,
                                        0,
                                        readBuffer.size
                                    )

                                } catch (_: Exception) {

                                    -1
                                }

                            if (read > 0) {

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

                            } else if (read < 0) {

                                break
                            }
                        }

                        if (
                            filled ==
                            AUDIO_PACKET_SIZE
                        ) {

                            try {

                                val packet =
                                    DatagramPacket(
                                        packetBuffer,
                                        AUDIO_PACKET_SIZE,
                                        address,
                                        SERVER_PORT
                                    )

                                socket.send(
                                    packet
                                )

                            } catch (_: Exception) {

                                break
                            }
                        }
                    }

                } catch (e: Exception) {

                    println(
                        "WALKIE $WALKIE_VERSION: 录音异常=${e.message}"
                    )

                } finally {

                    isSpeaking =
                        false

                    releaseAudioEffects()

                    try {
                        recorder?.stop()
                    } catch (_: Exception) {
                    }

                    try {
                        recorder?.release()
                    } catch (_: Exception) {
                    }

                    if (
                        audioRecord ===
                        recorder
                    ) {
                        audioRecord = null
                    }

                    setupSpeakerOutput()
                }
            }
    }

    /*
     * ============================================================
     * 音频效果
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

        noiseSuppressor = null
        automaticGainControl = null
        acousticEchoCanceler = null
    }

    private fun stopRecording() {

        isSpeaking =
            false

        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null

        releaseAudioEffects()

        setupSpeakerOutput()
    }

    /*
     * ============================================================
     * 音频播放
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
            !playbackQueue.offer(data)
        ) {

            playbackQueue.poll()
            playbackQueue.offer(data)
        }
    }

    private fun applyPlaybackGain(
        input: ByteArray
    ): ByteArray {

        if (
            PLAYBACK_GAIN <= 1.0f ||
            input.size < 2 ||
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
                    .toInt() and 0xFF

            val high =
                input[index + 1]
                    .toInt()

            var sample =
                (high shl 8) or low

            if (
                sample >
                32767
            ) {
                sample -= 65536
            }

            val amplified =
                (
                        sample.toFloat() *
                                PLAYBACK_GAIN
                        ).toInt()
                    .coerceIn(
                        -32768,
                        32767
                    )

            output[index] =
                (
                        amplified and 0xFF
                        ).toByte()

            output[index + 1] =
                (
                        (amplified shr 8) and 0xFF
                        ).toByte()

            index += 2
        }

        return output
    }

    private fun startPlaybackWorker() {

        if (
            playbackJob?.isActive == true
        ) {
            return
        }

        playbackJob =
            serviceScope.launch {

                var started =
                    false

                while (
                    isActive &&
                    !shuttingDown
                ) {

                    if (!started) {

                        while (
                            isActive &&
                            !shuttingDown &&
                            playbackQueue.size <
                            PLAYBACK_START_BUFFER_PACKETS
                        ) {

                            delay(5)
                        }

                        if (
                            !isActive ||
                            shuttingDown
                        ) {
                            break
                        }

                        started =
                            true
                    }

                    val raw =
                        playbackQueue.poll(
                            300,
                            TimeUnit.MILLISECONDS
                        )

                    if (raw == null) {

                        started =
                            false

                        continue
                    }

                    val track =
                        audioTrack

                    if (
                        track == null ||
                        track.state !=
                        AudioTrack.STATE_INITIALIZED
                    ) {

                        recreateAudioPlayer()

                        started =
                            false

                        continue
                    }

                    if (
                        track.playState !=
                        AudioTrack.PLAYSTATE_PLAYING
                    ) {

                        try {

                            track.play()

                        } catch (_: Exception) {

                            recreateAudioPlayer()

                            started =
                                false

                            continue
                        }
                    }

                    try {

                        track.write(
                            applyPlaybackGain(raw),
                            0,
                            raw.size,
                            AudioTrack.WRITE_BLOCKING
                        )

                    } catch (e: Exception) {

                        println(
                            "WALKIE $WALKIE_VERSION: 播放异常=${e.message}"
                        )

                        recreateAudioPlayer()

                        started =
                            false
                    }
                }
            }
    }

    private fun createAudioPlayer() {

        releaseAudioPlayer()

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

        if (minBuffer <= 0) {
            return
        }

        val bufferSize =
            max(
                minBuffer * 2,
                AUDIO_PACKET_SIZE * 8
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

        try {

            val track =
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

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                track.release()
                return
            }

            val speaker =
                findBuiltInSpeaker()

            if (speaker != null) {

                try {

                    track.setPreferredDevice(
                        speaker
                    )

                } catch (_: Exception) {
                }
            }

            audioTrack =
                track

            try {
                track.setVolume(
                    1.0f
                )
            } catch (_: Exception) {
            }

            track.play()

            startPlaybackWorker()

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: 创建播放设备失败=${e.message}"
            )
        }
    }

    private fun recreateAudioPlayer() {

        releaseAudioPlayer()

        if (!shuttingDown) {
            createAudioPlayer()
        }
    }

    private fun releaseAudioPlayer() {

        playbackQueue.clear()

        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null
    }

    /*
     * ============================================================
     * 扬声器
     * ============================================================
     */

    private fun setupSpeakerOutput() {

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

                if (speaker != null) {

                    try {

                        audioManager
                            .setCommunicationDevice(
                                speaker
                            )

                    } catch (_: Exception) {
                    }
                }

            } else {

                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn =
                    true
            }

        } catch (_: Exception) {
        }
    }

    private fun findBuiltInSpeaker():
            AudioDeviceInfo? {

        return try {

            val audioManager =
                getSystemService(
                    Context.AUDIO_SERVICE
                ) as AudioManager

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                audioManager
                    .getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    )
                    .firstOrNull {
                        it.type ==
                                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }

            } else {

                null
            }

        } catch (_: Exception) {

            null
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

        isConnected =
            connected

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
            udpSocket = null
        }

        talkRequesting = false
        talkAllowed = false
        isSpeaking = false

        stopRecording()
        releaseAudioPlayer()

        setConnected(false)
    }

    private fun cleanupConnection() {

        talkRequesting = false
        talkAllowed = false
        isSpeaking = false

        stopRecording()
        closeSocket()
        releaseAudioPlayer()

        serverAddress = null

        currentChannel = "public"
        currentChannelOnlineCount = 0
        currentChannelPrivate = false
        currentChannelRequirePassword = false

        channelSwitching = false

        cachedChannelInfoList =
            ArrayList()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        setConnected(false)
    }

    private fun closeSocket() {

        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }

        udpSocket = null
    }

    private fun stopAll() {

        shuttingDown = true

        try {

            if (isConnected) {

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
        networkJob = null

        stopRecording()
        closeSocket()
        releaseAudioPlayer()

        talkRequesting = false
        talkAllowed = false
        isSpeaking = false
        isConnected = false

        serverIp = null
        serverAddress = null

        currentChannel = "public"
        currentChannelOnlineCount = 0
        currentChannelPrivate = false
        currentChannelRequirePassword = false

        channelSwitching = false

        cachedChannelInfoList =
            ArrayList()

        pendingCreateChannelName = ""
        pendingCreateChannelPassword = ""

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        setConnected(false)
    }

    /*
     * ============================================================
     * Notification
     * ============================================================
     */

    private fun createNotification():
            Notification {

        val text =
            if (isConnected) {

                "频道：$currentChannel  👥 ${currentChannelOnlineCount}人"

            } else {

                "正在连接服务器"
            }

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "WALKIE V11"
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setOngoing(true)
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

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
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

        serviceScope.cancel()

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

        super.onDestroy()
    }
}