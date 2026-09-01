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
import android.media.ToneGenerator
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
import java.util.ArrayDeque

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.abs

class WalkieService : Service() {

    companion object {

        private const val WALKIE_VERSION = "V23.1"

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

        /*
         * 20ms PCM
         * 16000Hz * 20ms = 320 samples
         * 320 * 2 = 640 bytes
         */
        private const val AUDIO_PACKET_SIZE =
            640

        /*
         * UDP KEEPALIVE。
         */
        private const val KEEP_ALIVE_INTERVAL =
            5000L

        /*
         * 500ms接收超时。
         * 可以及时检查KEEPALIVE和服务器活动状态。
         */
        private const val SOCKET_RECEIVE_TIMEOUT =
            500

        /*
         * 服务器超过30秒没有任何返回，
         * 判定当前Socket连接失效。
         */
        private const val SERVER_ACTIVITY_TIMEOUT =
            120000L

        /*
         * V20.2：
         * 重连速度保留原来的快速策略。
         */
        private const val INITIAL_RECONNECT_INTERVAL =
            300L

        private const val MAX_RECONNECT_INTERVAL =
            1500L

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


        /*
         * ============================================================
         * 播放参数
         * ============================================================
         */

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

                /*
         * ============================================================
         * V23.1 弱网音频协议
         * ============================================================
         *
         * Header:
         *
         * 4 bytes  MAGIC     = W23A
         * 4 bytes  STREAM_ID
         * 4 bytes  SEQUENCE
         * N bytes  OPUS
         *
         * StreamID：
         *
         * 区分：
         *
         * 1. 不同说话人
         * 2. Service重新启动
         * 3. 手机重新连接
         *
         * Sequence：
         *
         * 检测：
         *
         * 1. 丢包
         * 2. 乱序
         * 3. 重复包
         * ============================================================
         */

        private const val AUDIO_V231_MAGIC =
            "W23A"

        private const val AUDIO_V231_HEADER_SIZE =
            12

        private const val AUDIO_V231_JITTER_CAPACITY =
            8

        private const val AUDIO_V231_MAX_WAIT_MS =
            60L
        private const val MAX_OPUS_PACKET_SIZE =
            1208

        private const val AUDIO_V231_MAX_PACKET_SIZE =
            MAX_OPUS_PACKET_SIZE +
                    AUDIO_V231_HEADER_SIZE

        private const val MAX_DECODED_PCM_SAMPLES =
            4096

        /*
         * ============================================================
         * 本地身份
         * ============================================================
         */

        private const val DEVICE_PREFS_NAME =
            "walkie_device_identity"

        private const val DEVICE_ID_KEY =
            "device_id"

        private const val PROFILE_PREFS_NAME =
            "walkie_profile_v20"

        private const val NICKNAME_KEY =
            "nickname"

        /*
         * ============================================================
         * UDP协议
         * ============================================================
         */

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

        /*
         * ============================================================
         * 用户协议
         * ============================================================
         */

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

    private lateinit var devicePreferences:
            SharedPreferences

    private lateinit var profilePreferences:
            SharedPreferences

    /*
     * ============================================================
     * 身份
     * ============================================================
     */

    private var deviceId =
        ""

    private var nickname =
        ""

    private var myUserId =
        ""

    private var myUsername =
        ""

    /*
     * ============================================================
     * UDP
     * ============================================================
     */

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

    private var activeNetwork:
            Network? =
        null

    /*
     * ============================================================
     * V20.2 网络连接生命周期控制
     * ============================================================
     *
     * 核心原则：
     *
     * 1. 一个Service只能有一个networkJob
     * 2. 一个networkJob只能有一个UDP Socket
     * 3. onAvailable不能直接重复启动连接
     * 4. onLost使旧连接失效
     * 5. generation防止旧连接清理新连接
     */

    private val connectionLock =
        Any()

    private var connectionGeneration =
        0L

    private var networkCallback:
            ConnectivityManager.NetworkCallback? =
        null

    private var networkJob:
            Job? =
        null

    /*
     * ============================================================
     * 其他Job
     * ============================================================
     */

    private var channelRefreshJob:
            Job? =
        null

    private var backgroundDiagnosticJob:
            Job? =
        null

    private var backgroundHeartbeatCount =
        0L

    private var udpKeepAliveCount =
        0L

    private var udpReceiveCount =
        0L

    /*
     * ============================================================
     * V21 网络质量
     * ============================================================
     */

    private val networkStatsLock = Any()

    private val pingPending =
        HashMap<Long, Long>()

    private val pingResults =
        ArrayDeque<Boolean>()

    private var pingSequence =
        0L

    private var lastNetworkPingTime =
        0L

    private var lastNetworkBroadcastTime =
        0L

    private var networkLatencyMs =
        -1L

    private var networkJitterMs =
        -1L

    private var networkLossPercent =
        100f

    private var networkQuality =
        "检测中"

    private var txAudioWindowBytes =
        0L

    private var txAudioWindowStart =
        0L

    private var networkBitrateKbps =
        0f

    private var networkDownloadBitrateKbps =
        0f

    private var rxAudioWindowBytes =
        0L

    private var rxAudioWindowStart =
        0L

    private var adaptivePlaybackRecoveryPackets =
        PLAYBACK_RECOVERY_BUFFER_PACKETS

    /*
     * V21：
     * 连续 Opus 解码失败次数。
     *
     * 防止网络抖动/损坏包连续出现时，
     * 播放端一直沿用旧的稳定状态。
     */
        /*
     * ============================================================
     * V23.1 音频流状态
     * ============================================================
     */

    private val audioV231StreamId =
        UUID.randomUUID()
            .leastSignificantBits
            .and(
                0xFFFF_FFFFL
            )

    private var audioV231TxSequence =
        0L

    private var audioV231RxStreamId =
        -1L

    private var audioV231ExpectedSequence =
        -1L

    private var audioV231LostPackets =
        0L

    private var audioV231ReorderedPackets =
        0L

    private var audioV231DuplicatePackets =
        0L

    private var audioV231GapStartTime =
        0L

    private val audioV231JitterLock =
        Any()

    private val audioV231JitterBuffer =
        java.util.TreeMap<Long, ByteArray>()
private var consecutiveDecodeFailures =
        0

    /*
     * ============================================================
     * 用户列表
     * ============================================================
     */

    private var currentUserList =
        ArrayList<UserInfo>()

    /*
     * ============================================================
     * 播放
     * ============================================================
     */

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

    private var playbackWorkerStarting =
        false

    private var playbackRecoveryRequested =
        false

    private var lastUnderrunCount =
        0

    /*
     * ============================================================
     * 录音
     * ============================================================
     */

    private var audioRecord:
            AudioRecord? =
        null

    private var recordJob:
            Job? =
        null

    private val audioRecordLock =
        Any()

    /*
 * V21：
 * 防止旧录音协程尚未完全退出时，
 * 新的 PTT 又启动第二个 AudioRecord。
 */
    private var recordingStarting =
        false

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

    private var talkRequesting =
        false

    private var talkAllowed =
        false

    private var isSpeaking =
        false

    private var shuttingDown =
        false

    private var lastKeepAliveTime =
        0L

    private var lastServerActivityTime =
        0L

    private var currentChannel =
        "public"

    private var reconnectChannel =
        ""

    private var reconnectChannelPassword =
        ""

    private var currentChannelOnlineCount =
        0

    private var currentChannelPrivate =
        false

    private var currentChannelRequirePassword =
        false

    private var channelSwitching =
        false

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

        profilePreferences =
            getSharedPreferences(
                PROFILE_PREFS_NAME,
                Context.MODE_PRIVATE
            )

        deviceId =
            loadOrCreateDeviceId()

        nickname =
            loadNickname()

        println(
            "WALKIE $WALKIE_VERSION: Service启动 " +
                    "DeviceID=${deviceLogId()} " +
                    "nickname=$nickname"
        )

        initializeOpus()

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
                    "WALKIE $WALKIE_VERSION: " +
                            "ACTION_START " +
                            "device=${deviceLogId()} " +
                            "nickname=$nickname " +
                            "ip=$incomingIp"
                )

                /*
                 * V20.2：
                 *
                 * 重复点击“连接”不会再创建第二个Socket。
                 */
                if (
                    !incomingIp.isNullOrBlank()
                ) {

                    startConnection(
                        incomingIp
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
     * DeviceID / Nickname
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

    private fun loadNickname():
            String {

        return try {

            profilePreferences
                .getString(
                    NICKNAME_KEY,
                    ""
                )
                ?.trim()
                ?.take(20)
                ?: ""

        } catch (_: Exception) {

            ""
        }
    }

    private fun setNickname(
        value: String
    ) {

        val clean =
            cleanNickname(
                value
            )

        if (
            clean.isBlank()
        ) {

            return
        }

        nickname =
            clean

        try {

            profilePreferences
                .edit()
                .putString(
                    NICKNAME_KEY,
                    clean
                )
                .apply()

        } catch (_: Exception) {
        }

        println(
            "WALKIE $WALKIE_VERSION: 设置昵称=$nickname"
        )

        broadcastMyUserInfo()

        if (
            isConnected
        ) {

            sendLoginAsync()
        }
    }

    private fun cleanNickname(
        value: String
    ):
            String {

        var result =
            value.trim()

        result =
            result
                .replace(
                    ":",
                    ""
                )
                .replace(
                    ";",
                    ""
                )
                .replace(
                    ",",
                    ""
                )
                .replace(
                    "\n",
                    ""
                )
                .replace(
                    "\r",
                    ""
                )

        return result
            .take(20)
            .trim()
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
     * 用户同步
     * ============================================================
     */

    private fun sendLoginAsync() {

        if (
            !isConnected ||
            shuttingDown
        ) {

            return
        }

        serviceScope.launch {

            sendLoginNow()
        }
    }

    private fun sendLoginNow() {

        val currentNickname =
            cleanNickname(
                nickname
            )

        if (
            currentNickname.isBlank()
        ) {

            sendMessageNow(
                "$MSG_LOGIN:$deviceId"
            )

        } else {

            sendMessageNow(
                "$MSG_LOGIN:$deviceId:$currentNickname"
            )
        }

        println(
            "WALKIE $WALKIE_VERSION: 已发送登录昵称=$currentNickname"
        )
    }

    private fun handleUserOk(
        text: String
    ) {

        val payload =
            text.substringAfter(
                "$MSG_USER_OK:",
                ""
            )

        if (
            payload.isBlank()
        ) {

            return
        }

        val parts =
            payload.split(
                ":",
                limit = 3
            )

        if (
            parts.isNotEmpty()
        ) {

            myUserId =
                parts
                    .getOrNull(0)
                    ?.trim()
                    ?: myUserId
        }

        if (
            parts.size >= 2
        ) {

            myUsername =
                parts[1]
                    .trim()

            if (
                myUsername.isNotBlank() &&
                !myUsername.startsWith(
                    "USER-"
                )
            ) {

                nickname =
                    myUsername

                try {

                    profilePreferences
                        .edit()
                        .putString(
                            NICKNAME_KEY,
                            myUsername
                        )
                        .apply()

                } catch (_: Exception) {
                }
            }
        }

        broadcastMyUserInfo()

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "USER_OK id=$myUserId " +
                    "username=$myUsername " +
                    "channel=${parts.getOrNull(2).orEmpty()}"
        )
    }

    private fun handleUserStatus(
        text: String
    ) {

        val payload =
            text.substringAfter(
                "$MSG_USER_STATUS:",
                ""
            )

        if (
            payload.isBlank()
        ) {

            return
        }

        val parts =
            payload.split(
                ":",
                limit = 5
            )

        if (
            parts.isNotEmpty()
        ) {

            val id =
                parts
                    .getOrNull(0)
                    ?.trim()
                    .orEmpty()

            if (
                id.isNotBlank()
            ) {

                myUserId =
                    id
            }
        }

        if (
            parts.size >= 2
        ) {

            val username =
                parts[1]
                    .trim()

            if (
                username.isNotBlank()
            ) {

                myUsername =
                    username

                if (
                    !username.startsWith(
                        "USER-"
                    )
                ) {

                    nickname =
                        username

                    try {

                        profilePreferences
                            .edit()
                            .putString(
                                NICKNAME_KEY,
                                username
                            )
                            .apply()

                    } catch (_: Exception) {
                    }
                }
            }
        }

        broadcastMyUserInfo()
    }

    private fun handleChannelMembers(
        text: String
    ) {

        val payload =
            text.substringAfter(
                "$MSG_CHANNEL_MEMBERS:",
                ""
            )

        /*
  * V21：
  * payload 为空时不能直接 return。
  *
  * 某些情况下服务器可能表示：
  *
  * 当前频道存在
  * 但是当前没有任何成员。
  *
  * 这种情况下必须把在线人数明确刷新成 0，
  * 不能继续保留上一轮频道的旧人数。
  */
        if (
            payload.isBlank()
        ) {

            currentUserList =
                ArrayList()

            currentChannelOnlineCount =
                0

            broadcastUserList()

            broadcastChannelStatus(
                "频道：$currentChannel，在线 0 人"
            )

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "收到空成员列表，当前频道在线人数=0"
            )

            return
        }
        val firstColon =
            payload.indexOf(':')

        if (
            firstColon < 0
        ) {

            return
        }

        val channelName =
            payload
                .substring(
                    0,
                    firstColon
                )
                .trim()

        if (
            channelName.isBlank() ||
            channelName != currentChannel
        ) {

            return
        }

        val memberText =
            payload.substring(
                firstColon + 1
            )

        val result =
            ArrayList<UserInfo>()

        if (
            memberText.isNotBlank()
        ) {

            for (
            item in memberText.split(";")
            ) {

                val cleanItem =
                    item.trim()

                if (
                    cleanItem.isBlank()
                ) {

                    continue
                }

                val parts =
                    cleanItem.split(
                        ",",
                        limit = 4
                    )

                if (
                    parts.size < 2
                ) {

                    continue
                }

                val id =
                    parts[0]
                        .trim()

                val username =
                    parts[1]
                        .trim()
                        .ifBlank {
                            "未命名用户"
                        }

                if (
                    id.isBlank()
                ) {

                    continue
                }

                result.add(
                    UserInfo(
                        userId = id,
                        username = username
                    )
                )
            }
        }

        val distinctResult =
            result
                .distinctBy {
                    it.userId
                }
                .sortedWith(
                    compareBy<UserInfo> {
                        it.userId != myUserId
                    }.thenBy {
                        it.username
                    }
                )

        currentUserList =
            ArrayList(
                distinctResult
            )

        currentChannelOnlineCount =
            distinctResult.size

        broadcastUserList()

        broadcastChannelStatus(
            "频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
        )

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "在线人员 channel=$channelName " +
                    "count=${distinctResult.size}"
        )
    }

    private fun broadcastMyUserInfo() {

        val intent =
            Intent(
                ACTION_MY_USER_INFO
            )

        intent.setPackage(
            packageName
        )

        intent.putExtra(
            EXTRA_MY_USER_ID,
            myUserId
        )

        intent.putExtra(
            EXTRA_MY_USERNAME,
            if (
                myUsername.isNotBlank()
            ) {

                myUsername

            } else {

                nickname
            }
        )

        sendBroadcast(
            intent
        )
    }

    private fun broadcastUserList() {

        val intent =
            Intent(
                ACTION_USER_LIST
            )

        intent.setPackage(
            packageName
        )

        val list =
            ArrayList<String>()

        for (
        user in currentUserList
        ) {

            list.add(
                user.userId +
                        "|" +
                        user.username
            )
        }

        intent.putStringArrayListExtra(
            EXTRA_USER_LIST,
            list
        )

        sendBroadcast(
            intent
        )
    }

    private fun clearUserList() {

        currentUserList =
            ArrayList()

        broadcastUserList()
    }

    /*
     * ============================================================
     * Opus
     * ============================================================
     */

        /*
     * ============================================================
     * V23.1 构建音频包
     * ============================================================
     */

    private fun buildV231AudioPacket(
        opusData: ByteArray
    ): ByteArray {

        val sequence =
            audioV231TxSequence and
                    0xFFFF_FFFFL

        val result =
            ByteArray(
                AUDIO_V231_HEADER_SIZE +
                        opusData.size
            )

        result[0] =
            'W'.code.toByte()

        result[1] =
            '2'.code.toByte()

        result[2] =
            '3'.code.toByte()

        result[3] =
            'A'.code.toByte()

        val streamId =
            audioV231StreamId and
                    0xFFFF_FFFFL

        result[4] =
            ((streamId shr 24) and 0xFF).toByte()

        result[5] =
            ((streamId shr 16) and 0xFF).toByte()

        result[6] =
            ((streamId shr 8) and 0xFF).toByte()

        result[7] =
            (streamId and 0xFF).toByte()

        result[8] =
            ((sequence shr 24) and 0xFF).toByte()

        result[9] =
            ((sequence shr 16) and 0xFF).toByte()

        result[10] =
            ((sequence shr 8) and 0xFF).toByte()

        result[11] =
            (sequence and 0xFF).toByte()

        System.arraycopy(
            opusData,
            0,
            result,
            AUDIO_V231_HEADER_SIZE,
            opusData.size
        )

        audioV231TxSequence =
            (
                    audioV231TxSequence +
                            1L
                    ) and
                    0xFFFF_FFFFL

        return result
    }

    /*
     * ============================================================
     * V23.1 解析音频包
     * ============================================================
     */

    private fun parseV231AudioPacket(
        packet: ByteArray
    ): Triple<Long, Long, ByteArray>? {

        if (
            packet.size <=
            AUDIO_V231_HEADER_SIZE
        ) {

            return null
        }

        if (
            packet[0] != 'W'.code.toByte() ||
            packet[1] != '2'.code.toByte() ||
            packet[2] != '3'.code.toByte() ||
            packet[3] != 'A'.code.toByte()
        ) {

            return null
        }

        val streamId =
            (
                    ((packet[4].toLong() and 0xFF) shl 24) or
                            ((packet[5].toLong() and 0xFF) shl 16) or
                            ((packet[6].toLong() and 0xFF) shl 8) or
                            (packet[7].toLong() and 0xFF)
                    ) and
                    0xFFFF_FFFFL

        val sequence =
            (
                    ((packet[8].toLong() and 0xFF) shl 24) or
                            ((packet[9].toLong() and 0xFF) shl 16) or
                            ((packet[10].toLong() and 0xFF) shl 8) or
                            (packet[11].toLong() and 0xFF)
                    ) and
                    0xFFFF_FFFFL

        val opusLength =
            packet.size -
                    AUDIO_V231_HEADER_SIZE

        if (
            opusLength <= 0 ||
            opusLength > MAX_OPUS_PACKET_SIZE
        ) {

            return null
        }

        val opus =
            ByteArray(
                opusLength
            )

        System.arraycopy(
            packet,
            AUDIO_V231_HEADER_SIZE,
            opus,
            0,
            opusLength
        )

        return Triple(
            streamId,
            sequence,
            opus
        )
    }

    private fun resetV231AudioJitter() {

        synchronized(
            audioV231JitterLock
        ) {

            audioV231JitterBuffer.clear()

            audioV231RxStreamId =
                -1L

            audioV231ExpectedSequence =
                -1L

            audioV231GapStartTime =
                0L
        }
    }

    private fun isV231SequenceAhead(
        sequence: Long,
        expected: Long
    ): Boolean {

        val diff =
            (
                    sequence -
                            expected
                    ) and
                    0xFFFF_FFFFL

        return diff != 0L &&
                diff < 0x8000_0000L
    }

    /*
     * ============================================================
     * V23.1 乱序 / 丢包处理
     * ============================================================
     */

    private fun reorderV231Audio(
        streamId: Long,
        sequence: Long,
        opusData: ByteArray
    ): ByteArray? {

        synchronized(
            audioV231JitterLock
        ) {

            /*
             * 新音频流：
             *
             * 新说话人
             * 或Service重新启动
             */
            if (
                audioV231RxStreamId !=
                streamId
            ) {

                audioV231JitterBuffer.clear()

                audioV231RxStreamId =
                    streamId

                audioV231ExpectedSequence =
                    sequence

                audioV231GapStartTime =
                    0L

                println(
                    "WALKIE AUDIO: " +
                            "V23.1 新音频流 " +
                            "stream=$streamId"
                )

                audioV231ExpectedSequence =
                    (
                            sequence +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                return opusData
            }

            /*
             * 正常连续包。
             */
            if (
                sequence ==
                audioV231ExpectedSequence
            ) {

                audioV231ExpectedSequence =
                    (
                            audioV231ExpectedSequence +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                audioV231GapStartTime =
                    0L

                return opusData
            }

            /*
             * 已经过期/重复。
             */
            if (
                !isV231SequenceAhead(
                    sequence,
                    audioV231ExpectedSequence
                )
            ) {

                audioV231DuplicatePackets++

                return null
            }

            /*
             * 重复缓存包。
             */
            if (
                audioV231JitterBuffer.containsKey(
                    sequence
                )
            ) {

                audioV231DuplicatePackets++

                return null
            }

            /*
             * 缓冲区满了：
             * 淘汰最早未来包。
             */
            if (
                audioV231JitterBuffer.size >=
                AUDIO_V231_JITTER_CAPACITY
            ) {

                audioV231JitterBuffer.pollFirstEntry()

                audioV231LostPackets++
            }

            audioV231JitterBuffer[
                sequence
            ] =
                opusData

            audioV231ReorderedPackets++

            if (
                audioV231GapStartTime ==
                0L
            ) {

                audioV231GapStartTime =
                    System.currentTimeMillis()
            }

            /*
             * 缺的包刚好补到。
             */
            val expectedPacket =
                audioV231JitterBuffer[
                    audioV231ExpectedSequence
                ]

            if (
                expectedPacket != null
            ) {

                audioV231JitterBuffer.remove(
                    audioV231ExpectedSequence
                )

                audioV231ExpectedSequence =
                    (
                            audioV231ExpectedSequence +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                audioV231GapStartTime =
                    0L

                return expectedPacket
            }

            /*
             * 缺包等待时间达到60ms：
             * 判定缺失。
             */
            val now =
                System.currentTimeMillis()

            if (
                now -
                        audioV231GapStartTime >=
                AUDIO_V231_MAX_WAIT_MS
            ) {

                val lostSequence =
                    audioV231ExpectedSequence

                audioV231LostPackets++

                audioV231ExpectedSequence =
                    (
                            audioV231ExpectedSequence +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                audioV231GapStartTime =
                    now

                println(
                    "WALKIE AUDIO: " +
                            "V23.1 丢包 seq=$lostSequence " +
                            "lost=$audioV231LostPackets"
                )

                val nextPacket =
                    audioV231JitterBuffer.remove(
                        audioV231ExpectedSequence
                    )

                if (
                    nextPacket != null
                ) {

                    audioV231ExpectedSequence =
                        (
                                audioV231ExpectedSequence +
                                        1L
                                ) and
                                0xFFFF_FFFFL

                    audioV231GapStartTime =
                        0L

                    return nextPacket
                }
            }

            return null
        }
    }

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

            opusEncoder =
                null
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

            opusDecoder =
                null
        }
    }

    /*
     * ============================================================
     * 后台诊断
     * ============================================================
     */

    private fun startBackgroundDiagnostic() {

        if (
            backgroundDiagnosticJob?.isActive ==
            true
        ) {

            return
        }

        backgroundDiagnosticJob =
            serviceScope.launch {

                while (
                    serviceScope.isActive &&
                    !shuttingDown
                ) {

                    delay(
                        5000L
                    )

                    backgroundHeartbeatCount++

                    println(
                        "WALKIE BG: alive " +
                                "count=$backgroundHeartbeatCount " +
                                "connected=$isConnected " +
                                "keepalive=$udpKeepAliveCount " +
                                "rx=$udpReceiveCount " +
                                "queue=${playbackQueue.size} " +
                                "users=${currentUserList.size} " +
                                "socketPort=${udpSocket?.localPort ?: -1} " +
                                "generation=$connectionGeneration " +
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
     * 网络监听
     * ============================================================
     *
     * V20.2关键修复：
     *
     * 原来的onAvailable：
     *
     * closeSocket()
     * networkJob?.cancel()
     * startConnection()
     *
     * 可能与connectOnce()同时竞争，
     * 导致一个Service短时间创建多个Socket。
     *
     * 现在：
     *
     * onAvailable只负责确认网络恢复。
     * 如果没有连接任务才启动连接。
     * 如果已经有连接任务，绝不重复创建。
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
                        shuttingDown ||
                        ip.isNullOrBlank()
                    ) {

                        return
                    }

                    /*
                     * 这里故意不closeSocket()
                     * 不cancel旧networkJob。
                     *
                     * 旧连接如果已经失效，
                     * 它自己的Socket生命周期会负责退出。
                     *
                     * 如果旧连接仍然健康，
                     * 更不能重复创建新Socket。
                     */

                    serviceScope.launch {

                        delay(
                            200L
                        )

                        if (
                            shuttingDown
                        ) {

                            return@launch
                        }

                        if (
                            isConnected &&
                            udpSocket?.isClosed ==
                            false
                        ) {

                            println(
                                "WALKIE $WALKIE_VERSION: " +
                                        "网络恢复但现有UDP仍健康，" +
                                        "不创建新Socket"
                            )

                            return@launch
                        }

                        startConnection(
                            ip
                        )
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

                    /*
                     * 先让当前连接状态失效。
                     */
                    setConnected(
                        false
                    )

                    /*
                     * 使当前generation立即失效。
                     *
                     * 这样旧连接在finally里不会
                     * 清理未来的新连接。
                     */
                    synchronized(
                        connectionLock
                    ) {

                        connectionGeneration++

                        networkJob?.cancel()

                        networkJob =
                            null
                    }

                    closeSocket()

                    clearUserList()

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "网络断开，旧连接已作废"
                    )
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

            println(
                "WALKIE $WALKIE_VERSION: 网络监听注册成功"
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
     * 判断连接generation是否仍有效
     * ============================================================
     */

    private fun isConnectionGenerationCurrent(
        generation: Long
    ):
            Boolean {

        return synchronized(
            connectionLock
        ) {

            connectionGeneration ==
                    generation
        }
    }

    /*
     * ============================================================
     * V20.2连接入口
     * ============================================================
     */

    private fun startConnection(
        ip: String
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

            serverIp =
                cleanIp

            /*
             * 已经有连接任务：
             * 绝对不能再创建第二个。
             */
            if (
                networkJob?.isActive ==
                true
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "连接任务已存在，忽略重复连接请求"
                )

                return
            }

            /*
             * 新连接generation。
             */
            val generation =
                connectionGeneration + 1L

            connectionGeneration =
                generation

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "启动唯一连接任务 " +
                        "generation=$generation " +
                        "$cleanIp:$SERVER_PORT"
            )

            networkJob =
                serviceScope.launch {

                    runConnectionLoop(
                        cleanIp,
                        generation
                    )

                    /*
                     * 只有自己这一代连接任务
                     * 才有资格把networkJob清空。
                     */
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

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "连接任务结束 " +
                                "generation=$generation"
                    )
                }
        }
    }

    /*
     * ============================================================
     * 统一连接循环
     * ============================================================
     */

    private suspend fun runConnectionLoop(
        ip: String,
        generation: Long
    ) {

        var reconnectDelay =
            INITIAL_RECONNECT_INTERVAL

        while (
            serviceScope.isActive &&
            !shuttingDown &&
            isConnectionGenerationCurrent(
                generation
            )
        ) {

            try {

                connectOnce(
                    ip,
                    generation
                )

                reconnectDelay =
                    INITIAL_RECONNECT_INTERVAL

            } catch (
                e: Exception
            ) {

                if (
                    !shuttingDown &&
                    isConnectionGenerationCurrent(
                        generation
                    )
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "网络异常=${e.message} " +
                                "generation=$generation"
                    )
                }
            }

            if (
                !serviceScope.isActive ||
                shuttingDown ||
                !isConnectionGenerationCurrent(
                    generation
                )
            ) {

                break
            }

            cleanupConnection(
                generation
            )

            if (
                !isNetworkAvailable
            ) {

                delay(
                    1000L
                )

                continue
            }

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
                    MAX_RECONNECT_INTERVAL
                ) {

                    MAX_RECONNECT_INTERVAL

                } else {

                    reconnectDelay * 2L
                }
        }
    }

    /*
     * ============================================================
     * UDP连接
     * ============================================================
     */

    private fun connectOnce(
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

        val socket =
            DatagramSocket()
        /*
 * ============================================================
 * V21：
 * 将 UDP Socket 绑定到当前 Android Network。
 *
 * 这样在 Wi-Fi / 5G 切换时，
 * 当前连接生命周期可以明确知道自己使用的是哪一张网卡。
 *
 * 如果当前没有可用 activeNetwork，
 * 则保持系统默认路由。
 * ============================================================
 */
        val network =
            activeNetwork

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.M &&
            network != null
        ) {

            try {

                network.bindSocket(
                    socket
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "UDP Socket 已绑定当前Network=$network " +
                            "localPort=${socket.localPort}"
                )

            } catch (
                e: Exception
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "绑定当前Network失败=${e.message}"
                )
            }
        }

        /*
         * 创建Socket以后再次检查generation。
         *
         * 防止刚创建完Socket，
         * 网络就已经切换。
         */
        if (
            !isConnectionGenerationCurrent(
                generation
            ) ||
            shuttingDown
        ) {

            try {
                socket.close()
            } catch (_: Exception) {
            }

            return
        }

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

        /*
         * ========================================================
         * 唯一Socket注册
         * ========================================================
         */

        synchronized(
            connectionLock
        ) {

            /*
             * 理论上这里不应该存在另一个Socket。
             *
             * 如果真的存在，绝不覆盖。
             */
            val existing =
                udpSocket

            if (
                existing != null &&
                !existing.isClosed
            ) {

                try {
                    socket.close()
                } catch (_: Exception) {
                }

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "检测到已有UDP Socket，" +
                            "拒绝创建第二个Socket"
                )

                return
            }

            if (
                connectionGeneration !=
                generation ||
                shuttingDown
            ) {

                try {
                    socket.close()
                } catch (_: Exception) {
                }

                return
            }

            udpSocket =
                socket
        }

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "UDP localPort=${socket.localPort} " +
                    "generation=$generation"
        )

        try {

            createAudioPlayer()

            /*
             * HELLO只发送一次。
             */
            sendMessageNow(
                "$MSG_HELLO:$deviceId:$WALKIE_VERSION"
            )

            val now =
                System.currentTimeMillis()

            resetNetworkStats()
            lastNetworkPingTime = now

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
                !socket.isClosed &&
                isConnectionGenerationCurrent(
                    generation
                )
            ) {

                val currentTime =
                    System.currentTimeMillis()

                expireNetworkPings(currentTime)

                if (
                    isConnected &&
                    currentTime - lastNetworkPingTime >= NETWORK_PING_INTERVAL
                ) {
                    sendNetworkPing(currentTime)
                    lastNetworkPingTime = currentTime
                }

                updateNetworkBitrate(currentTime)

                /*
                 * KEEPALIVE。
                 *
                 * 必须使用当前socket。
                 */
                if (
                    currentTime -
                    lastKeepAliveTime >=
                    KEEP_ALIVE_INTERVAL
                ) {

                    sendMessageNow(
                        "$MSG_KEEP_ALIVE:$deviceId"
                    )

                    udpKeepAliveCount++

                    println(
                        "WALKIE UDP: " +
                                "keepalive count=$udpKeepAliveCount " +
                                "port=${socket.localPort} " +
                                "generation=$generation"
                    )

                    lastKeepAliveTime =
                        currentTime
                }

                /*
                 * 服务器活动超时。
                 */
                if (
                    isConnected &&
                    currentTime -
                    lastServerActivityTime >=
                    SERVER_ACTIVITY_TIMEOUT
                ) {

                    println(
                        "WALKIE UDP: " +
                                "server timeout " +
                                "port=${socket.localPort}"
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
                            "WALKIE UDP: " +
                                    "rx count=$udpReceiveCount " +
                                    "bytes=$length " +
                                    "port=${socket.localPort}"
                        )
                    }

                    val text =
                        String(
                            packet.data,
                            packet.offset,
                            length,
                            Charsets.UTF_8
                        )

                    /*
                     * ==================================================
                     * 连接成功
                     * ==================================================
                     */

                    if (
                        text ==
                        MSG_CONNECTED
                    ) {

                        if (
                            !isConnectionGenerationCurrent(
                                generation
                            )
                        ) {

                            continue
                        }

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

                            /*
                             * 登录。
                             */
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

                        continue
                    }

                    /*
                     * KEEPALIVE应答。
                     */
                    if (
                        text ==
                        MSG_KEEP_ALIVE
                    ) {

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_NET_PONG:"
                        )
                    ) {

                        handleNetworkPong(text)

                        continue
                    }

                    /*
                     * ==================================================
                     * 用户
                     * ==================================================
                     */

                    if (
                        text.startsWith(
                            "$MSG_USER_OK:"
                        )
                    ) {

                        handleUserOk(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_USER_STATUS:"
                        )
                    ) {

                        handleUserStatus(
                            text
                        )

                        continue
                    }

                    if (
                        text.startsWith(
                            "$MSG_CHANNEL_MEMBERS:"
                        )
                    ) {

                        handleChannelMembers(
                            text
                        )

                        continue
                    }

                    /*
                     * ==================================================
                     * 频道
                     * ==================================================
                     */

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

                    /*
                     * ==================================================
                     * 抢麦
                     * ==================================================
                     */

                    if (
                        text ==
                        MSG_TALK_OK
                    ) {

                        if (
                            talkRequesting &&
                            !isSpeaking &&
                            isConnected &&
                            !shuttingDown &&
                            isConnectionGenerationCurrent(
                                generation
                            )
                        ) {

                            /*
  * ============================================================
  * V21：抢麦成功提示
  * ============================================================
  *
  * 服务器明确返回 TALK_OK 后：
  *
  * 1. 结束 REQUESTING
  * 2. 设置 ALLOWED
  * 3. 更新界面
  * 4. 播放一声短提示音
  * 5. 再开始录音
  *
  * 这样用户能明确知道：
  * “已经抢到麦，可以开始说话了。”
  */
                            talkRequesting =
                                false

                            talkAllowed =
                                true

                            setTalkStatus(
                                TALK_STATUS_ALLOWED
                            )

                            /*
                             * 抢麦成功提示音。
                             *
                             * 必须在开始录音之前播放，
                             * 避免提示音被麦克风采集后又通过网络发出去。
                             */
                            playTalkGrantedTone()

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
                     * 其他WALKIE控制包不进入Opus解码。
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
                     * 音频
                     * ==================================================
                     */

                    if (
                        length <= 0 ||
                        length >
                        AUDIO_V231_MAX_PACKET_SIZE
                    ) {

                        println(
                            "WALKIE AUDIO: " +
                                    "drop invalid packet size=$length"
                        )

                        continue
                    }

                    val decoder =
                        opusDecoder
                            ?: continue

                    val audioData =
                        ByteArray(
                            length
                        )

                    recordAudioReceive(
                        length
                    )

                    System.arraycopy(
                        packet.data,
                        packet.offset,
                        audioData,
                        0,
                        length
                    )

                    /*
                     * ==================================================
                     * V23.1：
                     * 新协议：
                     *
                     * W23A + StreamID + Sequence + Opus
                     *
                     * 旧协议：
                     *
                     * 直接Opus
                     *
                     * 两种格式同时支持。
                     * ==================================================
                     */

                    val parsedV231 =
                        parseV231AudioPacket(
                            audioData
                        )

                    val opusPayload =
                        if (
                            parsedV231 != null
                        ) {

                            reorderV231Audio(
                                parsedV231.first,
                                parsedV231.second,
                                parsedV231.third
                            )

                        } else {

                            audioData
                        }

                    if (
                        opusPayload == null
                    ) {

                        continue
                    }

                    val pcmData =
                        try {

                            decoder.decode(
                                opusPayload
                            )

                        } catch (e: Throwable) {

                            println(
                                "WALKIE AUDIO: " +
                                        "decoder exception=${e.message}"
                            )

                            null
                        }

                    if (
                        pcmData == null ||
                        pcmData.isEmpty()
                    ) {

                        /*
                         * V21：
                         * 记录连续解码失败。
                         *
                         * 单个坏包直接丢弃；
                         * 连续多个失败时，
                         * 请求播放器进入恢复模式。
                         */
                        consecutiveDecodeFailures =
                            (
                                    consecutiveDecodeFailures + 1
                                    )
                                .coerceAtMost(
                                    20
                                )

                        if (
                            consecutiveDecodeFailures >= 3
                        ) {

                            playbackRecoveryRequested =
                                true

                            println(
                                "WALKIE AUDIO: " +
                                        "连续Opus解码失败=" +
                                        consecutiveDecodeFailures +
                                        "，请求播放恢复"
                            )
                        }

                        continue
                    }

                    /*
                     * 当前 Opus 已经成功解码，
                     * 连续失败计数恢复。
                     */
                    consecutiveDecodeFailures =
                        0

                    if (
                        pcmData.size >
                        MAX_DECODED_PCM_SAMPLES
                    ) {

                        println(
                            "WALKIE AUDIO: " +
                                    "drop invalid PCM=${pcmData.size}"
                        )

                        continue
                    }

                    /*
                     * 解码后的PCM必须是偶数样本对应的有效16bit数据。
                     * 同时过滤极端异常长度，避免异常包把播放器拖死。
                     */
                    if (
                        pcmData.size < 80 ||
                        pcmData.size > SAMPLE_RATE / 5
                    ) {
                        println(
                            "WALKIE AUDIO: drop abnormal PCM=${pcmData.size}"
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

                    /*
                     * V21：
                     * 非预期 UDP 异常不再继续复用当前连接。
                     *
                     * 直接抛出，让 connectOnce() 的 finally
                     * 安全释放当前 Socket，
                     * 再由 runConnectionLoop() 负责自动重连。
                     *
                     * 这样比：
                     *
                     * 异常 -> continue -> 坏Socket继续运行
                     *
                     * 更可靠。
                     */
                    throw SocketException(
                        "UDP receive processing failed: ${e.message}"
                    )
                }
            }

        } finally {

            /*
             * 只有当前generation的Socket
             * 才能被清理。
             *
             * 防止旧Socket把新Socket置空。
             */
            cleanupSocket(
                socket
            )
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
            channelRefreshJob?.isActive ==
            true
        ) {

            return
        }

        channelRefreshJob =
            serviceScope.launch {

                while (
                    serviceScope.isActive &&
                    !shuttingDown
                ) {

                    delay(
                        10000L
                    )

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

        /*
  * ============================================================
  * V21：
  * CHANNEL_LIST 中的在线人数属于频道列表快照，
  * 不能覆盖 CHANNEL_MEMBERS 刚刚同步得到的真实人数。
  *
  * 当前频道：
  *
  * CHANNEL_MEMBERS
  *        ↓
  * currentChannelOnlineCount
  *
  * 才是当前频道在线人数的优先来源。
  */
        cachedChannelInfoList
            .firstOrNull {
                it.name ==
                        currentChannel
            }?.let {

                /*
                 * 频道类型信息仍然可以从 CHANNEL_LIST 更新。
                 */
                currentChannelPrivate =
                    it.isPrivate

                currentChannelRequirePassword =
                    it.isPrivate

                /*
                 * 只有当前还没有真实成员列表时，
                 * 才使用 CHANNEL_LIST 的人数作为备用值。
                 */
                if (
                    currentUserList.isEmpty()
                ) {

                    currentChannelOnlineCount =
                        it.onlineCount
                }
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

        sendBroadcast(
            intent
        )
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

        clearUserList()

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

        clearUserList()

        pendingCreateChannelName =
            ""

        pendingCreateChannelPassword =
            ""

        updateCurrentChannelInfo()

        broadcastChannelStatus(
            "已进入频道：$currentChannel，在线 ${currentChannelOnlineCount} 人"
        )

        sendMessageAsync(
            MSG_CHANNEL_MEMBERS
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
            )
                .trim()

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

            clearUserList()

            setTalkStatus(
                TALK_STATUS_RELEASED
            )

            broadcastChannelStatus(
                "频道已删除：$deletedChannel，已返回 public"
            )

            if (
                isConnected
            ) {

                sendMessageAsync(
                    MSG_CHANNEL_MEMBERS
                )
            }
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

    private fun handleChannelError(
        text: String
    ) {

        val error =
            text.substringAfter(
                "$MSG_CHANNEL_ERROR:",
                "UNKNOWN"
            )
                .trim()

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

        clearUserList()

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

        if (
            isConnected
        ) {

            sendMessageAsync(
                MSG_CHANNEL_MEMBERS
            )
        }
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

            list.add(
                info
            )
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

    /*
  * ============================================================
  * V21 UDP 安全发送
  * ============================================================
  *
  * 发送失败时：
  *
  * 1. 记录日志
  * 2. 确认这个 Socket 仍然是当前 Socket
  * 3. 关闭失效 Socket
  *
  * 让 connectOnce() 的接收循环尽快退出，
  * 再交给现有的自动重连机制重新建立：
  *
  * 新 Socket
  *     ↓
  * 新 Network
  *     ↓
  * HELLO
  *     ↓
  * LOGIN
  *     ↓
  * 恢复频道
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

        } catch (
            e: Throwable
        ) {

            if (
                !shuttingDown
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "UDP发送失败=${e.message}，" +
                            "当前Socket将进入自动恢复"
                )
            }

            /*
             * ====================================================
             * V21：
             * 只有当前全局 Socket 还是刚才发送失败的这个实例，
             * 才允许关闭它。
             *
             * 防止：
             *
             * 旧 Socket 发送失败
             *        ↓
             * 新 Socket 已经建立
             *        ↓
             * 旧任务误把新 Socket 关闭
             * ====================================================
             */
            synchronized(
                connectionLock
            ) {

                if (
                    udpSocket ===
                    socket
                ) {

                    try {

                        socket.close()

                    } catch (_: Throwable) {
                    }

                    udpSocket =
                        null

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "发送失败，当前UDP Socket已失效"
                    )
                }
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

    /*
     * ============================================================
     * PTT
     * ============================================================
     */

    /*
   * ============================================================
   * V21 PTT 抢麦请求
   * ============================================================
   *
   * 抢麦流程：
   *
   * 按住
   *   ↓
   * REQUESTING
   *   ↓
   * WALKIE_TALK_START
   *   ↓
   * 等待服务器 WALKIE_TALK_OK / BUSY
   *
   * 如果服务器 3 秒内没有任何结果：
   *
   *   ↓
   * 自动取消本次抢麦
   *   ↓
   * 恢复 NONE
   *
   * 防止按钮长期卡在“抢麦中”。
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

        /*
         * 标记正在抢麦。
         */
        talkRequesting =
            true

        talkAllowed =
            false

        isSpeaking =
            false

        setTalkStatus(
            TALK_STATUS_REQUESTING
        )

        /*
         * 立即发送抢麦请求。
         */
        sendMessageAsync(
            MSG_TALK_START
        )

        /*
         * ========================================================
         * V21：抢麦超时保护
         * ========================================================
         *
         * 不单独创建 Job 保存，
         * 只在超时后检查当前状态。
         *
         * 如果此时已经：
         *
         * TALK_OK
         * 或
         * TALK_BUSY
         * 或
         * 用户已经松开
         *
         * talkRequesting 都已经变成 false，
         * 所以不会误取消新的讲话。
         */
        serviceScope.launch {

            delay(
                3000L
            )

            if (
                talkRequesting &&
                !talkAllowed &&
                isConnected &&
                !shuttingDown
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "PTT 抢麦 3 秒无响应，自动超时"
                )

                talkRequesting =
                    false

                talkAllowed =
                    false

                isSpeaking =
                    false

                setTalkStatus(
                    TALK_STATUS_RELEASED
                )

                /*
                 * 告诉服务器：
                 * 本次没有继续等待抢麦。
                 */
                sendMessageAsync(
                    MSG_TALK_STOP
                )
            }
        }
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

        /*
 * V21：
 * 启动录音流程加锁。
 *
 * 即使上一个 recordJob 正在取消，
 * 新的 PTT 也不能同时创建第二个 AudioRecord。
 */
        if (
            recordJob?.isActive ==
            true
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

        synchronized(
            audioRecordLock
        ) {

            if (
                recordingStarting
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "录音启动流程仍在进行，忽略重复请求"
                )

                return
            }

            if (
                recordJob?.isActive ==
                true
            ) {

                return
            }

            recordingStarting =
                false
        }
        /*
         * V21：
         * 到这里才确认具备启动录音的条件。
         *
         * 防止旧录音协程还未完全退出时，
         * 新 PTT 创建第二个 AudioRecord。
         */
        synchronized(
            audioRecordLock
        ) {

            if (
                recordingStarting
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "录音启动仍在进行，忽略重复启动"
                )

                return
            }

            recordingStarting =
                false
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

                        recordingStarting =
                            false

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
                                "WALKIE $WALKIE_VERSION: " +
                                        "AudioRecord创建失败=${e.message}"
                            )

                            null
                        }

                    if (
                        recorder == null
                    ) {

                        recordingStarting =
                            false

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

                    /*
 * ========================================================
 * V21：
 * AudioRecord 启动必须确认成功。
 *
 * 即使服务器已经返回 TALK_OK，
 * 如果本机 AudioRecord 没有真正进入 RECORDING 状态，
 * 也必须立即撤销本次讲话状态。
 * ========================================================
 */

                    var recordingStarted =
                        false

                    try {

                        synchronized(
                            audioRecordLock
                        ) {

                            /*
                             * 在锁内启动，
                             * 避免 stopRecording() 同时操作同一实例。
                             */
                            if (
                                audioRecord ===
                                recorder &&
                                recorder.state ==
                                AudioRecord.STATE_INITIALIZED
                            ) {

                                recorder.startRecording()

                                recordingStarted =
                                    recorder.recordingState ==
                                            AudioRecord.RECORDSTATE_RECORDING
                            }
                        }

                    } catch (
                        e: Throwable
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "AudioRecord.startRecording异常=" +
                                    e.message
                        )

                        recordingStarted =
                            false
                    }

                    if (
                        !recordingStarted
                    ) {

                        /*
                         * ====================================================
                         * 启动失败：
                         * 立即撤销讲话状态。
                         * ====================================================
                         */
                        isSpeaking =
                            false

                        talkAllowed =
                            false

                        talkRequesting =
                            false

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "AudioRecord 启动失败，取消本次讲话"
                        )

                        setTalkStatus(
                            TALK_STATUS_RELEASED
                        )

                        /*
 * V21：
 * 本机录音启动失败，但服务器已经给了 TALK_OK。
 *
 * 必须通知服务器释放当前话权，
 * 否则服务器可能继续认为当前用户占麦。
 */
                        sendMessageAsync(
                            MSG_TALK_STOP
                        )

                        /*
                         * 让 finally 负责统一释放当前 recorder。
                         */
                        return@launch
                    }

                    println(
                        "WALKIE $WALKIE_VERSION: ★开始录音★"
                    )

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
                                        "WALKIE $WALKIE_VERSION: " +
                                                "AudioRecord.read异常=${e.message}"
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

                                /*
                                 * AudioRecord 出现负值，
                                 * 表示读取发生异常或录音状态已经失效。
                                 *
                                 * 退出当前录音循环，
                                 * 由 finally 统一 stop/release。
                                 */
                                println(
                                    "WALKIE $WALKIE_VERSION: " +
                                            "AudioRecord.read返回=$read，结束录音"
                                )

                                break

                            } else {

                                /*
                                 * READ_NON_BLOCKING 下 read == 0
                                 * 是正常情况：
                                 *
                                 * 当前暂时没有可读取的数据。
                                 *
                                 * 稍微让出 CPU，避免高速空转。
                                 */
                                Thread.sleep(
                                    4L
                                )
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
                                    "WALKIE $WALKIE_VERSION: " +
                                            "Opus编码异常=${e.message}"
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

                            val framedAudio =
                            buildV231AudioPacket(
                                opus
                            )

                        val packet =
                            DatagramPacket(
                                framedAudio,
                                framedAudio.size,
                                address,
                                SERVER_PORT
                            )

                        socket.send(
                            packet
                        )

                            recordAudioTransmit(
                                opus.size
                            )

                        } catch (e: Exception) {

                            println(
                                "WALKIE $WALKIE_VERSION: " +
                                        "OPUS发送失败=${e.message}"
                            )

                            break
                        }
                    }

                } catch (
                    e: Throwable
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "录音线程异常=${e.message}"
                    )

                } finally {

                    recordingStarting =
                        false

                    /*
                     * ========================================================
                     * V21：统一 AudioRecord 生命周期
                     * ========================================================
                     *
                     * stopRecording() 和这里的 finally 都可能触发。
                     *
                     * 因此必须：
                     *
                     * 1. 先停止录音状态
                     * 2. 加锁确认是不是当前实例
                     * 3. 只有仍然属于全局 audioRecord 的实例
                     *    才执行 stop/release
                     * 4. release 后清空全局引用
                     *
                     * 避免同一个 AudioRecord 被重复释放。
                     */

                    isSpeaking =
                        false

                    /*
                     * 保存当前实例。
                     */
                    val currentRecorder =
                        recorder

                    if (
                        currentRecorder != null
                    ) {

                        synchronized(
                            audioRecordLock
                        ) {

                            /*
                             * 只有全局 audioRecord 仍然指向
                             * 当前这个 recorder，才能由 finally 负责释放。
                             *
                             * 如果 stopRecording() 已经把它释放并清空，
                             * 这里就不能再次 release。
                             */
                            if (
                                audioRecord ===
                                currentRecorder
                            ) {

                                try {

                                    if (
                                        currentRecorder.recordingState ==
                                        AudioRecord.RECORDSTATE_RECORDING
                                    ) {

                                        currentRecorder.stop()
                                    }

                                } catch (
                                    e: Throwable
                                ) {

                                    println(
                                        "WALKIE $WALKIE_VERSION: " +
                                                "finally AudioRecord.stop异常=" +
                                                e.message
                                    )
                                }

                                /*
                                 * 先清除全局引用。
                                 *
                                 * 这样即使后续 release 出现异常，
                                 * 其他线程也不会继续把这个对象
                                 * 当作当前有效 AudioRecord 使用。
                                 */
                                audioRecord =
                                    null

                                try {

                                    currentRecorder.release()

                                } catch (
                                    e: Throwable
                                ) {

                                    println(
                                        "WALKIE $WALKIE_VERSION: " +
                                                "finally AudioRecord.release异常=" +
                                                e.message
                                    )
                                }
                            }
                        }
                    }

                    /*
                     * AudioEffect 最后释放。
                     *
                     * 不让 AudioRecord 和 AudioEffect
                     * 处于交叉释放状态。
                     */
                    releaseAudioEffects()

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

    /*
  * ============================================================
  * V21 录音安全停止
  * ============================================================
  *
  * 核心原则：
  *
  * 1. 先让录音循环退出
  * 2. 再在 audioRecordLock 中停止/释放 AudioRecord
  * 3. 只有当前全局 audioRecord 仍然是这个实例时，
  *    才清空 audioRecord
  * 4. 最后释放 AudioEffect
  *
  * 避免：
  *
  * 录音协程 finally
  *        +
  * stopRecording()
  *        ↓
  * 同时 release 同一个 AudioRecord
  */
    /*
 * ============================================================
 * V21：抢麦成功提示音
 * ============================================================
 *
 * 使用系统 ToneGenerator 播放极短提示音。
 *
 * 不使用 AudioTrack 播放提示音，
 * 避免和语音播放 AudioTrack 生命周期互相干扰。
 */
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

                /*
                 * TONE_PROP_ACK：
                 * 用作短确认提示音。
                 *
                 * 持续时间控制在很短范围，
                 * 不影响正式讲话。
                 */
                toneGenerator.startTone(
                    ToneGenerator.TONE_PROP_ACK,
                    120
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "★抢麦成功提示音★"
                )

                /*
                 * 等提示音播放一小段后再释放。
                 */
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
    private fun stopRecording() {

        /*
         * 第一阶段：
         * 先通知录音循环立即退出。
         */
        isSpeaking =
            false

        recordingStarting =
            false

        /*
         * 取消当前录音 Job。
         *
         * 不在这里等待 Job 完全结束，
         * 因为 stopRecording() 可能本身就是在录音线程
         * 的控制路径中被调用。
         */
        recordJob?.cancel()

        recordJob =
            null

        /*
         * 第二阶段：
         * 获取当前 AudioRecord。
         */
        val recorder =
            synchronized(
                audioRecordLock
            ) {

                audioRecord
            }

        if (
            recorder == null
        ) {

            /*
             * 没有 AudioRecord，
             * 仍然需要确保 AudioEffect 被释放。
             */
            releaseAudioEffects()

            return
        }

        /*
         * 第三阶段：
         * 在同一把锁下完成 stop + release，
         * 防止其他线程同时操作这个实例。
         */
        synchronized(
            audioRecordLock
        ) {

            /*
             * 如果在等待锁的过程中，
             * 另外一个线程已经换了新的 AudioRecord，
             * 就不能碰新的实例。
             */
            if (
                audioRecord !==
                recorder
            ) {

                return@synchronized
            }

            try {

                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {

                    recorder.stop()
                }

            } catch (
                e: Throwable
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "AudioRecord.stop异常=${e.message}"
                )
            }

            try {

                recorder.release()

            } catch (
                e: Throwable
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "AudioRecord.release异常=${e.message}"
                )
            }

            /*
             * 只有确认全局引用还是这个实例，
             * 才清空。
             */
            if (
                audioRecord ===
                recorder
            ) {

                audioRecord =
                    null
            }
        }

        /*
         * 第四阶段：
         * AudioRecord 已经处理完后，
         * 再释放音频效果。
         */
        releaseAudioEffects()
    }

    /*
     * ============================================================
     * V21 网络质量
     * ============================================================
     */

    private fun resetNetworkStats() {

        synchronized(networkStatsLock) {
            pingPending.clear()
            pingResults.clear()
            pingSequence = 0L
        }

        networkLatencyMs = -1L
        networkJitterMs = -1L
        networkLossPercent = 100f
        networkBitrateKbps = 0f
        networkDownloadBitrateKbps = 0f
        networkQuality = if (isConnected) "检测中" else "离线"
        txAudioWindowBytes = 0L
        txAudioWindowStart = System.currentTimeMillis()
        rxAudioWindowBytes = 0L
        rxAudioWindowStart = txAudioWindowStart
        adaptivePlaybackRecoveryPackets = PLAYBACK_RECOVERY_BUFFER_PACKETS
        broadcastNetworkStatus(true)
    }

    private fun sendNetworkPing(now: Long) {

        val seq = synchronized(networkStatsLock) {
            pingSequence = (pingSequence + 1L) and 0x7FFF_FFFFL
            pingPending[pingSequence] = now
            pingSequence
        }

        sendMessageAsync(
            "$MSG_NET_PING:$seq:$now"
        )
    }

    private fun handleNetworkPong(text: String) {

        val payload =
            text.substringAfter(
                "$MSG_NET_PONG:",
                ""
            )

        val parts =
            payload.split(":")

        val sequence =
            parts.getOrNull(0)?.toLongOrNull()
                ?: return

        val sentAt = synchronized(networkStatsLock) {
            pingPending.remove(sequence)
        } ?: parts.getOrNull(1)?.toLongOrNull() ?: return

        val now =
            System.currentTimeMillis()

        val rtt =
            (now - sentAt).coerceIn(0L, 60000L)

        val previous = networkLatencyMs

        networkLatencyMs =
            if (previous < 0L) {
                rtt
            } else {
                ((previous * 0.70) + (rtt * 0.30)).toLong()
            }

        if (previous >= 0L) {
            val diff = abs(rtt - previous).toDouble()
            networkJitterMs =
                if (networkJitterMs < 0L) {
                    diff.toLong()
                } else {
                    ((networkJitterMs * 0.75) + (diff * 0.25)).toLong()
                }
        } else {
            networkJitterMs = 0L
        }

        addPingResult(true)
        updateNetworkQuality()
        adaptPlaybackBuffer()
        broadcastNetworkStatus(false)
    }

    private fun expireNetworkPings(now: Long) {

        val expired = ArrayList<Long>()

        synchronized(networkStatsLock) {
            val iterator = pingPending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value >= NETWORK_PING_TIMEOUT) {
                    expired.add(entry.key)
                    iterator.remove()
                }
            }
        }

        if (expired.isNotEmpty()) {
            repeat(expired.size) {
                addPingResult(false)
            }
            updateNetworkQuality()
            adaptPlaybackBuffer()
            broadcastNetworkStatus(false)
        }
    }

    private fun addPingResult(success: Boolean) {

        synchronized(networkStatsLock) {
            pingResults.addLast(success)
            while (pingResults.size > NETWORK_PING_WINDOW) {
                pingResults.removeFirst()
            }

            val total = pingResults.size
            val lost = pingResults.count { !it }
            networkLossPercent =
                if (total == 0) 100f
                else lost * 100f / total.toFloat()
        }
    }

    private fun updateNetworkQuality() {

        val latency = networkLatencyMs
        val loss = networkLossPercent
        val jitter = networkJitterMs.coerceAtLeast(0L)

        networkQuality = when {
            !isConnected -> "离线"
            latency < 0L -> "检测中"
            loss >= 20f || latency >= 300L || jitter >= 100L -> "较差"
            loss >= 8f || latency >= 180L || jitter >= 50L -> "一般"
            loss >= 3f || latency >= 100L || jitter >= 25L -> "良好"
            else -> "优秀"
        }
    }

    private fun adaptPlaybackBuffer() {

        val loss = networkLossPercent
        val latency = networkLatencyMs
        val jitter = networkJitterMs

        adaptivePlaybackRecoveryPackets = when {
            loss >= 12f || latency >= 250L || jitter >= 70L -> 6
            loss >= 5f || latency >= 150L || jitter >= 35L -> 4
            else -> PLAYBACK_RECOVERY_BUFFER_PACKETS
        }.coerceIn(3, 6)
    }

    private fun recordAudioTransmit(bytes: Int) {

        val now = System.currentTimeMillis()

        if (txAudioWindowStart <= 0L) {
            txAudioWindowStart = now
        }

        txAudioWindowBytes += bytes.toLong()

        updateNetworkBitrate(now)
    }

    private fun recordAudioReceive(bytes: Int) {

        val now = System.currentTimeMillis()

        if (rxAudioWindowStart <= 0L) {
            rxAudioWindowStart = now
        }

        rxAudioWindowBytes += bytes.toLong()

        updateNetworkBitrate(now)
    }

    private fun updateNetworkBitrate(now: Long) {

        val txStart = txAudioWindowStart
        val rxStart = rxAudioWindowStart
        val windowStart = minOf(
            if (txStart > 0L) txStart else now,
            if (rxStart > 0L) rxStart else now
        )
        val elapsed = now - windowStart

        if (elapsed < NETWORK_BITRATE_WINDOW) {
            return
        }

        if (txStart > 0L) {
            val txElapsed = (now - txStart).coerceAtLeast(1L)
            networkBitrateKbps =
                (txAudioWindowBytes * 8.0 / (txElapsed / 1000.0) / 1000.0).toFloat()
            txAudioWindowBytes = 0L
            txAudioWindowStart = now
        }

        if (rxStart > 0L) {
            val rxElapsed = (now - rxStart).coerceAtLeast(1L)
            networkDownloadBitrateKbps =
                (rxAudioWindowBytes * 8.0 / (rxElapsed / 1000.0) / 1000.0).toFloat()
            rxAudioWindowBytes = 0L
            rxAudioWindowStart = now
        }

        broadcastNetworkStatus(false)
    }

    private fun broadcastNetworkStatus(force: Boolean) {

        val now = System.currentTimeMillis()

        if (!force &&
            now - lastNetworkBroadcastTime < NETWORK_STATUS_MIN_INTERVAL) {
            return
        }

        lastNetworkBroadcastTime = now

        val intent =
            Intent(ACTION_NETWORK_STATUS)
                .setPackage(packageName)
                .putExtra(
                    EXTRA_NETWORK_LATENCY,
                    networkLatencyMs
                )
                .putExtra(
                    EXTRA_NETWORK_LOSS,
                    networkLossPercent
                )
                .putExtra(
                    EXTRA_NETWORK_QUALITY,
                    networkQuality
                )
                .putExtra(
                    EXTRA_NETWORK_BITRATE,
                    networkBitrateKbps
                )
                .putExtra(
                    EXTRA_NETWORK_UPLOAD_BITRATE,
                    networkBitrateKbps
                )
                .putExtra(
                    EXTRA_NETWORK_DOWNLOAD_BITRATE,
                    networkDownloadBitrateKbps
                )
                .putExtra(
                    EXTRA_NETWORK_JITTER,
                    networkJitterMs
                )

        sendBroadcast(intent)
    }

    /*
     * ============================================================
     * 播放队列
     * ============================================================
     */

    /*
  * ============================================================
  * V21 播放队列：实时性 + 弱网平衡
  * ============================================================
  *
  * 原则：
  *
  * 1. 正常网络：
  *    尽量保持短队列，降低对讲延迟。
  *
  * 2. 网络一般：
  *    适当增加缓冲，减少短暂断音。
  *
  * 3. 网络较差：
  *    可以保留更多语音包，
  *    但绝不允许队列无限增长。
  *
  * 4. 队列超过当前允许上限时：
  *    丢掉最旧的语音包。
  *
  *    因为对讲机是实时通信，
  *    旧声音比丢掉旧声音更影响体验。
  */
    private fun enqueueAudio(
        data: ByteArray
    ) {

        /*
         * ========================================================
         * 基础数据安全检查
         * ========================================================
         */

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
         * ========================================================
         * 根据网络状态动态确定最大播放队列
         * ========================================================
         *
         * 大约按照：
         *
         * 8包  ≈ 160ms
         * 12包 ≈ 240ms
         * 16包 ≈ 320ms
         * 20包 ≈ 400ms
         *
         * 仍然保留实时对讲优先。
         */

        val latency =
            networkLatencyMs

        val loss =
            networkLossPercent

        val jitter =
            networkJitterMs

        val dynamicMaxQueue =
            when {

                /*
                 * 严重弱网：
                 * 稍微增加缓冲，避免频繁断音。
                 */
                loss >= 12f ||
                        latency >= 250L ||
                        jitter >= 70L -> {

                    20
                }

                /*
                 * 中等弱网。
                 */
                loss >= 5f ||
                        latency >= 150L ||
                        jitter >= 35L -> {

                    16
                }

                /*
                 * 网络良好。
                 */
                loss >= 3f ||
                        latency >= 100L ||
                        jitter >= 25L -> {

                    12
                }

                /*
                 * 优秀网络：
                 * 低延迟优先。
                 */
                else -> {

                    8
                }
            }
                .coerceIn(
                    8,
                    PLAYBACK_MAX_QUEUE_PACKETS
                )

        /*
         * ========================================================
         * 队列限流
         * ========================================================
         *
         * 队列过长以后，
         * 不继续无限堆积。
         *
         * 删除最旧的数据，
         * 保留最新到达的语音。
         */
        while (
            playbackQueue.size >=
            dynamicMaxQueue
        ) {

            val removed =
                playbackQueue.poll()

            if (
                removed ==
                null
            ) {

                break
            }
        }

        /*
         * ========================================================
         * 尝试加入最新语音
         * ========================================================
         */

        if (
            !playbackQueue.offer(
                data
            )
        ) {

            /*
             * 极端情况下队列刚好在并发线程中
             * 被填满，再主动淘汰最旧的一包。
             */
            playbackQueue.poll()

            playbackQueue.offer(
                data
            )
        }

        /*
  * ========================================================
  * V21：
  * 收到新语音时确认 AudioTrack 是否仍然可用。
  *
  * 如果后台长时间运行过程中 AudioTrack 已经失效，
  * 立即标记恢复，让 playbackLoop 自动重新创建。
  * ========================================================
  */

        val currentTrack =
            synchronized(
                audioTrackLock
            ) {

                audioTrack
            }

        if (
            currentTrack != null
        ) {

            val trackState =
                try {

                    currentTrack.state

                } catch (
                    _: Throwable
                ) {

                    AudioTrack.STATE_UNINITIALIZED
                }

            if (
                trackState !=
                AudioTrack.STATE_INITIALIZED
            ) {

                println(
                    "WALKIE AUDIO: " +
                            "收到语音时发现AudioTrack失效，" +
                            "请求自动恢复"
                )

                playbackRecoveryRequested =
                    true

            } else {

                /*
                 * AudioTrack 本身正常。
                 */
            }
        } else {

            /*
             * 没有 AudioTrack：
             * 让 playbackLoop 自动创建。
             */
            playbackRecoveryRequested =
                true
        }

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

        merged.addAll(
            frames
        )

        merged.addAll(
            existing
        )

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
     * 播放线程
     * ============================================================
     */

    private fun startPlaybackWorker() {

        synchronized(
            playbackWorkerLock
        ) {

            if (
                playbackJob?.isActive ==
                true
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

    private suspend fun playbackLoop() {

        var firstStart =
            true

        while (
            serviceScope.isActive &&
            !shuttingDown
        ) {

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

                    adaptivePlaybackRecoveryPackets

                } else {

                    1
                }

            /*
  * V21：
  * 播放缓冲等待不要每 4ms 一直唤醒。
  *
  * 正常播放：
  *      8ms 检查一次
  *
  * recovery：
  *      4ms 检查一次
  *
  * 这样正常后台等待时减少无意义 CPU 唤醒，
  * 同时恢复播放时仍保持较快响应。
  */
            val waitIntervalMs =
                if (
                    recovering
                ) {

                    4L

                } else {

                    8L
                }

            while (
                serviceScope.isActive &&
                !shuttingDown &&
                playbackQueue.size <
                requiredPackets
            ) {

                delay(
                    waitIntervalMs
                )
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

                delay(
                    25L
                )

                continue
            }

            if (
                firstStart ||
                recovering ||
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                /*
 * ============================================================
 * V21 恢复播放：优先保留最新语音
 * ============================================================
 *
 * recovery 时，如果队列里已经积压很多旧语音，
 * 不能把所有旧数据全部重新播放。
 *
 * 否则：
 *
 * 网络恢复
 * ↓
 * 播放历史积压
 * ↓
 * 延迟越来越大
 *
 * 所以：
 *
 * recovery 模式下，
 * 只取恢复所需要的最近几包数据。
 */

                val frames =
                    ArrayList<ByteArray>()

                /*
                 * 如果恢复时队列特别大，
                 * 先淘汰一部分最旧语音。
                 *
                 * 保留最多：
                 *
                 * requiredPackets + 2
                 *
                 * 这样可以快速回到实时状态，
                 * 又不会因为一次网络抖动直接把所有语音清空。
                 */
                val recoveryLimit =
                    (
                            requiredPackets + 2
                            )
                        .coerceAtMost(
                            adaptivePlaybackRecoveryPackets + 2
                        )

                while (
                    playbackQueue.size >
                    recoveryLimit
                ) {

                    playbackQueue.poll()
                }

                /*
                 * 重新取当前最新的一小段语音。
                 */
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
                                    "WALKIE AUDIO: " +
                                            "★V20.2起播★ " +
                                            "packets=${frames.size} " +
                                            "queue=${playbackQueue.size}"
                                )
                            }

                        } catch (
                            e: Throwable
                        ) {

                            println(
                                "WALKIE AUDIO: " +
                                        "起播/恢复异常=${e.message}"
                            )

                            writeSuccess =
                                false
                        }
                    }
                }

                if (
                    !writeSuccess
                ) {

                    requeueFront(
                        frames
                    )

                    handlePlaybackFailure(
                        track
                    )

                    playbackRecoveryRequested =
                        true

                    delay(
                        30L
                    )

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

            val first =
                try {

                    playbackQueue.poll(
                        90L,
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
                                    "WALKIE AUDIO: " +
                                            "★AudioTrack恢复播放★ " +
                                            "queue=${playbackQueue.size}"
                                )

                            } else {

                                failed =
                                    true
                            }

                        } else {

                            /*
                             * ========================================================
                             * V21：
                             * 每次正常写入语音之前，
                             * 重新确认当前 AudioTrack 的首选输出设备。
                             *
                             * 主要应对：
                             *
                             * 锁屏
                             * 蓝牙连接/断开
                             * 系统音频路由变化
                             * 前后台切换
                             *
                             * 不重新创建 AudioTrack，
                             * 只重新确认输出设备。
                             * ========================================================
                             */
                            try {

                                setTrackSpeaker(
                                    current
                                )

                            } catch (
                                e: Throwable
                            ) {

                                println(
                                    "WALKIE AUDIO: " +
                                            "播放前刷新扬声器失败=${e.message}"
                                )
                            }

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
                                    "WALKIE AUDIO: " +
                                            "AudioTrack.write失败=$result"
                                )

                                failed =
                                    true
                            }
                        }

                    } catch (
                        e: Throwable
                    ) {

                        println(
                            "WALKIE AUDIO: " +
                                    "正常播放异常=${e.message}"
                        )

                        failed =
                            true
                    }
                }
            }

            if (
                failed
            ) {

                requeueFront(
                    frames
                )

                handlePlaybackFailure(
                    track
                )

                playbackRecoveryRequested =
                    true

                delay(
                    25L
                )

                continue
            }

            /*
 * ============================================================
 * V21 underrun 恢复
 * ============================================================
 *
 * underrun 表示 AudioTrack 一时没有足够 PCM 数据。
 *
 * 发生后：
 *
 * 1. 记录 underrun
 * 2. 请求恢复缓冲
 * 3. 根据当前队列实际情况决定是否需要继续恢复
 *
 * 不无限扩大播放延迟。
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
                    "WALKIE AUDIO: " +
                            "★underrun +$delta " +
                            "total=$currentUnderrun " +
                            "queue=${playbackQueue.size} " +
                            "recoveryTarget=$adaptivePlaybackRecoveryPackets★"
                )

                lastUnderrunCount =
                    currentUnderrun

                /*
                 * 只有当前队列明显低于恢复目标时，
                 * 才需要真正进入 recovery。
                 *
                 * 如果队列已经有足够数据，
                 * 就不要反复进入恢复流程。
                 */
                if (
                    playbackQueue.size <
                    adaptivePlaybackRecoveryPackets
                ) {

                    playbackRecoveryRequested =
                        true

                } else {

                    /*
                     * 队列已经足够，
                     * 直接继续正常播放。
                     */
                    playbackRecoveryRequested =
                        false
                }
            }
        }
    }

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
            ByteArray(
                total
            )

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
  * V21 AudioTrack 安全恢复
  * ============================================================
  *
  * 播放过程中如果 AudioTrack 出现：
  *
  * 1. 状态失效
  * 2. play() 异常
  * 3. write() 失败后无法继续播放
  *
  * 不直接让整个播放线程退出。
  *
  * 当前实例失效：
  *     ↓
  * 安全释放
  *     ↓
  * 清空全局引用
  *     ↓
  * 标记需要恢复
  *     ↓
  * 下一轮 playbackLoop 自动重建 AudioTrack
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

        /*
         * ============================================================
         * V22.2 AudioTrack 自动重建
         * ============================================================
         *
         * 当 AudioTrack 因为：
         *
         * 1. underrun
         * 2. write失败
         * 3. 系统音频状态异常
         * 4. Harmony 后台调度变化
         *
         * 导致播放器进入不可正常工作的状态时，
         * 不再只执行 play()。
         *
         * 直接彻底释放当前 Track。
         *
         * 下一轮 playbackLoop()
         * 会通过 ensureAudioPlayer()
         * 自动创建新的 AudioTrack。
         */

        audioTrack =
            null

        try {

            if (
                track.playState ==
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                track.pause()
            }

        } catch (_: Throwable) {
        }

        try {

            track.flush()

        } catch (_: Throwable) {
        }

        try {

            track.release()

        } catch (_: Throwable) {
        }

        lastUnderrunCount =
            0

        println(
            "WALKIE AUDIO: " +
                    "V22.2 AudioTrack已彻底释放，准备自动重建"
        )
    }
}
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
                    "WALKIE AUDIO: " +
                            "getMinBufferSize失败=$minBuffer"
                )

                return
            }

            val bufferSize =
                maxOf(
                    minBuffer * 4,
                    AUDIO_PACKET_SIZE * 16
                )

            /*
  * ============================================================
  * V21：
  * AudioTrack 明确使用语音通信属性。
  *
  * 后台/锁屏场景下，
  * 系统更明确知道这是实时语音通信输出，
  * 不把它当普通音乐播放。
  * ============================================================
  */
            val attributes =
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .setFlags(
                        AudioAttributes.FLAG_LOW_LATENCY
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
                        "WALKIE AUDIO: " +
                                "AudioTrack创建失败=${e.message}"
                    )

                    return
                }

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                println(
                    "WALKIE AUDIO: " +
                            "AudioTrack状态异常=${track.state}"
                )

                try {

                    track.release()

                } catch (_: Exception) {
                }

                return
            }

            setTrackSpeaker(
                track
            )

            try {

                track.setVolume(
                    1.0f
                )

            } catch (_: Exception) {
            }

            /*
  * ============================================================
  * V21：
  * AudioTrack 创建成功以后立即确认播放状态。
  *
  * 不直接强制播放数据，
  * 但先把 Track 切换到 PLAYING 状态。
  *
  * 后续真正的 PCM 仍然由 playbackLoop 写入。
  * ============================================================
  */
            audioTrack =
                track

            try {

                track.play()

                println(
                    "WALKIE AUDIO: " +
                            "AudioTrack创建后已进入PLAYING状态"
                )

            } catch (
                e: Throwable
            ) {

                /*
                 * play() 失败：
                 * 当前 Track 不应继续作为有效播放器使用。
                 */
                audioTrack =
                    null

                try {

                    track.release()

                } catch (_: Throwable) {
                }

                println(
                    "WALKIE AUDIO: " +
                            "AudioTrack创建后PLAY失败=${e.message}"
                )

                return
            }

            lastUnderrunCount =
                getUnderrunCount(
                    track
                )

            println(
                "WALKIE AUDIO: " +
                        "AudioTrack创建成功 " +
                        "buffer=$bufferSize " +
                        "speaker=${findBuiltInSpeaker() != null}"
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
                "WALKIE AUDIO: " +
                        "设置AudioTrack扬声器失败=${e.message}"
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

            resetNetworkStats()

            stopChannelRefreshWorker()

            clearUserList()

            myUserId =
                ""

            myUsername =
                ""

            broadcastMyUserInfo()
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

    /*
     * ============================================================
     * Socket安全清理
     * ============================================================
     */

    private fun cleanupSocket(
        socket: DatagramSocket
    ) {

        try {

            socket.close()

        } catch (_: Exception) {
        }

        /*
         * 只有当前udpSocket就是这个socket，
         * 才允许清空全局引用。
         *
         * 防止：
         *
         * 旧Socket finally
         *        ↓
         * 把新Socket误清空
         */
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

    /*
     * ============================================================
     * 连接失败后的清理
     * ============================================================
     */

    private fun cleanupConnection(
        generation: Long
    ) {

        /*
         * 旧generation没有权限清理当前状态。
         */
        if (
            !isConnectionGenerationCurrent(
                generation
            )
        ) {

            return
        }

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
        /*
 * ============================================================
 * V21：连接断开后彻底清理旧播放状态
 * ============================================================
 *
 * 断线之前已经进入播放队列的语音，
 * 在重新连接以后已经没有实时意义。
 *
 * 必须全部清掉。
 */
        playbackQueue.clear()

        /*
         * 下一次收到新语音后，
         * 播放线程重新按照起播缓冲策略工作。
         */
        playbackRecoveryRequested =
            true

        /*
         * 重置 underrun 基准。
         *
         * 避免新连接建立后，
         * 把旧连接的 underrun 次数当成新的异常。
         */
        lastUnderrunCount =
            0

        /*
  * V21：
  * 断线清理时不要把当前频道强制改成 public。
  *
  * reconnectChannel 已经保存了上一次正常使用的频道，
  * 等重新连接成功后，由连接恢复流程重新加入该频道。
  *
  * 这样 Service 内部的频道状态不会在重连期间
  * 突然跳回 public。
  */
        if (
            reconnectChannel.isBlank() ||
            reconnectChannel == "public"
        ) {

            currentChannel =
                "public"
        }

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

        clearUserList()

        setTalkStatus(
            TALK_STATUS_RELEASED
        )

        setConnected(
            false
        )

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "连接清理完成 generation=$generation"
        )
    }

    /*
     * ============================================================
     * 关闭当前Socket
     * ============================================================
     */

    private fun closeSocket() {

        val socket =
            synchronized(
                connectionLock
            ) {

                val current =
                    udpSocket

                udpSocket =
                    null

                current
            }

        if (
            socket != null
        ) {

            try {

                socket.close()

            } catch (_: Exception) {
            }

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "UDP Socket 已关闭"
            )
        }
    }

    /*
     * ============================================================
     * 完全停止
     * ============================================================
     */

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

        /*
         * 先让所有旧连接generation失效。
         */
        synchronized(
            connectionLock
        ) {

            connectionGeneration++

            networkJob?.cancel()

            networkJob =
                null
        }

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

        clearUserList()

        myUserId =
            ""

        myUsername =
            ""

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

        broadcastMyUserInfo()

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

        updateNotification()

        println(
            "WALKIE $WALKIE_VERSION: Service通信已完全停止"
        )
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
                wakeLock?.isHeld ==
                false
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
                "WALKIE V20.2"
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
                wakeLock?.isHeld ==
                true
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








