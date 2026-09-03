package com.example.walkie

import com.example.walkie.audio.WalkieAudioPlayer
import com.example.walkie.audio.WalkieAudioPlayback
import com.example.walkie.network.WalkieNetworkMonitor
import com.example.walkie.network.WalkieNetworkStats
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
import android.media.ToneGenerator
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
            100

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

    /*
     * ============================================================
     * V24.9.2 UDP Manager
     * ============================================================
     *
     * 这一步先只把 Manager 接入 Service。
     *
     * 原来的 udpSocket 暂时继续保留。
     *
     * 后续步骤会再逐步把：
     *
     * 1. 创建 Socket
     * 2. UDP 接收
     * 3. UDP 发送
     * 4. 网络切换迁移
     *
     * 移交给 WalkieUdpManager。
     *
     * 采用边改边拆：
     *
     * 改一步
     *   ↓
     * 编译一次
     *   ↓
     * 没问题再继续
     * ============================================================
     */
    private val udpManager by lazy {
        WalkieUdpManager(
            scope = serviceScope,
            receiveTimeoutMs =
                SOCKET_RECEIVE_TIMEOUT,
            oldSocketGraceMs =
                3000L,
            logger = { message ->
                println(message)
            }
        )
    }

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

    private var lastNetworkPingTime =
        0L

    private var activeNetwork:
            Network? =
        null

    /*
 * ============================================================
 * V24.9.2 网络无缝迁移
 * ============================================================
 *
 * 参考 HarmonyOS 已验证方案：
 *
 * 旧 Socket 不立即关闭
 *      ↓
 * 新 Socket 绑定新 Network
 *      ↓
 * 切换 udpSocket
 *      ↓
 * HELLO × 5
 *      ↓
 * PING
 *      ↓
 * KEEPALIVE
 *      ↓
 * 旧 Socket 延迟 3 秒关闭
 *
 * 迁移期间：
 *
 * connected 不变
 * playback 不停止
 * channel 不清空
 * user list 不清空
 */
    private var networkMigrationJob:
            Job? =
        null

    private var handoffOldSocket:
            DatagramSocket? =
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

    private var networkMonitor:
            WalkieNetworkMonitor? =
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
     *
     * 网络质量统计独立到 WalkieNetworkStats。
     * WalkieService 只保留对外状态代理，避免业务代码被统计细节淹没。
     */

    private val networkStats by lazy {
        WalkieNetworkStats(
            serverPort = SERVER_PORT,
            pingMessagePrefix = MSG_NET_PING,
            pongMessagePrefix = MSG_NET_PONG,
            pingWindowSize = NETWORK_PING_WINDOW,
            pingTimeoutMs = NETWORK_PING_TIMEOUT,
            bitrateWindowMs = NETWORK_BITRATE_WINDOW,
            statusMinIntervalMs = NETWORK_STATUS_MIN_INTERVAL,
            defaultRecoveryPackets = PLAYBACK_RECOVERY_BUFFER_PACKETS,
            socketProvider = { udpSocket },
            serverAddressProvider = { serverAddress },
            isConnectedProvider = { isConnected },
            packageNameProvider = { packageName },
            context = applicationContext,
            actionNetworkStatus = ACTION_NETWORK_STATUS,
            extraLatency = EXTRA_NETWORK_LATENCY,
            extraLoss = EXTRA_NETWORK_LOSS,
            extraQuality = EXTRA_NETWORK_QUALITY,
            extraBitrate = EXTRA_NETWORK_BITRATE,
            extraUploadBitrate = EXTRA_NETWORK_UPLOAD_BITRATE,
            extraDownloadBitrate = EXTRA_NETWORK_DOWNLOAD_BITRATE,
            extraJitter = EXTRA_NETWORK_JITTER,
            logger = { message -> println(message) }
        )
    }

    private val networkLatencyMs: Long
        get() = networkStats.latencyMs

    private val networkJitterMs: Long
        get() = networkStats.jitterMs

    private val networkLossPercent: Float
        get() = networkStats.lossPercent

    private val networkQuality: String
        get() = networkStats.quality

    private val networkBitrateKbps: Float
        get() = networkStats.uploadBitrateKbps

    private val networkDownloadBitrateKbps: Float
        get() = networkStats.downloadBitrateKbps

    private val adaptivePlaybackRecoveryPackets: Int
        get() = networkStats.adaptiveRecoveryPackets

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

    private val audioPlayer by lazy {

        WalkieAudioPlayer(
            context = applicationContext,
            sampleRate = SAMPLE_RATE,
            packetSize = AUDIO_PACKET_SIZE,
            gain = PLAYBACK_GAIN
        ) { message ->

            println(
                message
            )
        }
    }

    private val audioPlayback by lazy {

        WalkieAudioPlayback(
            audioPlayer = audioPlayer,

            queueCapacity =
                PLAYBACK_QUEUE_CAPACITY,

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

                println(
                    message
                )
            }
        )
    }

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

                /*
                 * ========================================================
                 * V24.9.2 调试：
                 *
                 * 手动测试UDP无缝迁移。
                 *
                 * 不需要实际切换WiFi / 手机流量。
                 *
                 * 测试内容：
                 *
                 * 当前Socket
                 *      ↓
                 * 新Socket
                 *      ↓
                 * 新Socket接管
                 *      ↓
                 * HELLO × 5
                 *      ↓
                 * PING
                 *      ↓
                 * 旧Socket延迟关闭
                 * ========================================================
                 */
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
                    Comparator { left, right ->
                        val leftPriority =
                            if (left.userId == myUserId) 0 else 1
                        val rightPriority =
                            if (right.userId == myUserId) 0 else 1

                        when {
                            leftPriority != rightPriority ->
                                leftPriority.compareTo(rightPriority)

                            else ->
                                left.username.compareTo(right.username)
                        }
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
             * ============================================================
             * V24.9.1 音频流重新同步策略
             * ============================================================
             *
             * 正常情况：
             *
             * sequence == expected
             *        ↓
             * 直接播放
             *
             * 少量乱序 / 少量丢包：
             *
             * sequence领先expected几个包
             *        ↓
             * 暂存抖动缓存
             *        ↓
             * 等待缺失包
             *
             * 但是：
             *
             * 网络断开
             *      ↓
             * 自动重连
             *      ↓
             * 发送端sequence已经继续增长
             *      ↓
             * 接收端expected仍停留在很旧的位置
             *
             * 这时不能继续等待旧sequence。
             *
             * 否则会出现：
             *
             * seq=190 lost=918
             * seq=191 lost=922
             * seq=192 lost=927
             *
             * 这种“假性疯狂丢包”。
             *
             * 所以这里增加：
             *
             * 大跨度sequence自动重新同步。
             * ============================================================
             */

            /*
             * ============================================================
             * 新音频流：
             *
             * 可能是：
             *
             * 1. 新说话人
             * 2. Service重新启动
             * 3. 网络重新建立后服务器开始新的音频流
             *
             * 第一包直接作为新的播放起点。
             * ============================================================
             */
            if (
                audioV231RxStreamId !=
                streamId
            ) {

                audioV231JitterBuffer.clear()

                audioV231RxStreamId =
                    streamId

                audioV231ExpectedSequence =
                    (
                            sequence +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                audioV231GapStartTime =
                    0L

                println(
                    "WALKIE AUDIO: " +
                            "V23.1 新音频流 " +
                            "stream=$streamId " +
                            "seq=$sequence"
                )

                return opusData
            }

            /*
             * 当前已经存在有效的expected。
             */
            val expected =
                audioV231ExpectedSequence and
                        0xFFFF_FFFFL

            /*
             * ============================================================
             * 正常连续包
             * ============================================================
             */
            if (
                sequence ==
                expected
            ) {

                audioV231ExpectedSequence =
                    (
                            expected +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                audioV231GapStartTime =
                    0L

                return opusData
            }

            /*
             * ============================================================
             * 计算sequence向前距离
             *
             * 只在 sequence 被判断为“领先”时才使用。
             * ============================================================
             */
            val forwardDiff =
                (
                        sequence -
                                expected
                        ) and
                        0xFFFF_FFFFL

            /*
             * ============================================================
             * V24.9.1关键修复：
             *
             * 大跨度跳跃直接重新同步。
             *
             * 8是当前抖动缓存大小。
             *
             * 如果一次领先已经超过：
             *
             * 8 * 4 = 32包
             *
             * 基本可以认为不是普通乱序，
             * 而是网络切换/断线恢复造成的sequence断层。
             *
             * 此时：
             *
             * 旧expected
             *      ↓
             * 直接放弃
             *
             * 当前sequence
             *      ↓
             * 作为新的播放起点
             * ============================================================
             */
            val largeGapThreshold =
                AUDIO_V231_JITTER_CAPACITY * 4

            if (
                forwardDiff >
                largeGapThreshold.toLong() &&
                forwardDiff <
                0x8000_0000L
            ) {

                val skippedPackets =
                    forwardDiff - 1L

                audioV231LostPackets +=
                    skippedPackets

                audioV231JitterBuffer.clear()

                audioV231ExpectedSequence =
                    (
                            sequence +
                                    1L
                            ) and
                            0xFFFF_FFFFL

                audioV231GapStartTime =
                    0L

                println(
                    "WALKIE AUDIO: " +
                            "V23.1 检测到大跨度序号，" +
                            "立即重新同步 " +
                            "expected=$expected " +
                            "current=$sequence " +
                            "skip=$skippedPackets " +
                            "lost=$audioV231LostPackets"
                )

                return opusData
            }

            /*
             * ============================================================
             * 已经过期 / 重复包
             *
             * forwardDiff >= 0x80000000
             * 表示sequence实际上落后于expected。
             * ============================================================
             */
            if (
                forwardDiff >=
                0x8000_0000L
            ) {

                audioV231DuplicatePackets++

                return null
            }

            /*
             * ============================================================
             * 少量领先：
             *
             * 认为是正常乱序 / 少量丢包。
             * ============================================================
             */
            if (
                forwardDiff <=
                AUDIO_V231_JITTER_CAPACITY.toLong()
            ) {

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
                 * 缓冲区已经满了。
                 *
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
                 * 缺失包刚好已经在缓存里。
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
                 * ========================================================
                 * 缺包等待60ms。
                 * ========================================================
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
                                lostSequence +
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

            /*
             * ============================================================
             * 这里理论上是一个非常特殊的中间情况。
             *
             * 为了避免重排器长期卡死：
             *
             * 直接重新同步到当前sequence。
             * ============================================================
             */
            audioV231JitterBuffer.clear()

            audioV231ExpectedSequence =
                (
                        sequence +
                                1L
                        ) and
                        0xFFFF_FFFFL

            audioV231GapStartTime =
                0L

            println(
                "WALKIE AUDIO: " +
                        "V23.1 异常序号状态，" +
                        "强制重新同步 seq=$sequence"
            )

            return opusData
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
                                "queue=${audioPlayback.queueSize()} " +
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

        if (networkMonitor != null) {
            return
        }

        networkMonitor =
            WalkieNetworkMonitor(
                context = applicationContext,
                onAvailable = { network ->
                    handleNetworkAvailable(network)
                },
                onLost = { network ->
                    handleNetworkLost(network)
                },
                onCapabilitiesChanged = { network, capabilities ->
                    handleNetworkCapabilitiesChanged(
                        network,
                        capabilities
                    )
                },
                logger = { message ->
                    println(message)
                }
            )

        networkMonitor?.start()
    }

    private fun unregisterNetworkCallback() {

        networkMonitor?.stop()
        networkMonitor = null
        activeNetwork = null
    }

    private fun handleNetworkAvailable(
        network: Network
    ) {
        val previousNetwork = activeNetwork

        println(
            "WALKIE $WALKIE_VERSION: " +
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
            activeNetwork = network
            isNetworkAvailable = true

            val ip = serverIp
            if (
                shuttingDown ||
                ip.isNullOrBlank()
            ) {
                return
            }

            networkMigrationJob?.cancel()

            networkMigrationJob =
                serviceScope.launch {

                    if (shuttingDown) {
                        return@launch
                    }

                    if (activeNetwork != network) {
                        return@launch
                    }

                    if (
                        isConnected &&
                        udpManager.isOpen()
                    ) {
                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "当前UDP仍健康，不创建重复Socket"
                        )
                        return@launch
                    }

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "网络恢复，立即启动自动重连"
                    )

                    startConnection(ip)
                }

            return
        }

        /*
         * ========================================================
         * 检测到网络切换
         *
         * 这里开始使用“无缝迁移”：
         *
         * 1. 不关闭旧Socket
         * 2. 不清空用户列表
         * 3. 不修改 connected=false
         * 4. 不停止播放
         * 5. 新Socket建立成功后再切换
         * ========================================================
         */
        println(
            "WALKIE $WALKIE_VERSION: " +
                    "检测到网络切换 " +
                    "$previousNetwork -> $network，" +
                    "开始无缝迁移UDP"
        )

        activeNetwork = network
        isNetworkAvailable = true

        val ip = serverIp
        if (
            shuttingDown ||
            ip.isNullOrBlank()
        ) {
            return
        }

        serviceScope.launch {
            if (shuttingDown) {
                return@launch
            }

            if (activeNetwork != network) {
                return@launch
            }

            try {
                /*
                 * ====================================================
                 * 新Socket建立
                 * ====================================================
                 */
                udpManager.migrate(
                    ip = ip,
                    port = SERVER_PORT,
                    network = network
                )

                val newSocket =
                    udpManager.currentSocket()

                if (newSocket == null) {
                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "UDP无缝迁移失败：新Socket为空"
                    )
                    return@launch
                }

                synchronized(connectionLock) {
                    udpSocket = newSocket
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

                println(
                    "WALKIE $WALKIE_VERSION: " +
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
                repeat(5) { index ->

                    if (
                        shuttingDown ||
                        activeNetwork != network
                    ) {
                        return@launch
                    }

                    sendMessageNow(
                        "$MSG_HELLO:$deviceId"
                    )

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "网络迁移HELLO " +
                                "${index + 1}/5"
                    )

                    delay(80L)
                }

                /*
                 * ====================================================
                 * HELLO完成后发送PING
                 * ====================================================
                 */
                sendNetworkPing(
                    System.currentTimeMillis()
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "网络迁移PING已发送"
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "UDP无缝迁移完成，" +
                            "旧Socket将在宽限期后关闭"
                )

            } catch (e: Throwable) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "UDP无缝迁移失败=${e.message}"
                )

                /*
                 * 只有迁移失败才允许进入正常重连
                 */
                if (
                    !shuttingDown &&
                    activeNetwork == network
                ) {
                    synchronized(connectionLock) {
                        connectionGeneration++
                        networkJob?.cancel()
                        networkJob = null
                    }

                    closeSocket()

                    setConnected(false)

                    startConnection(ip)
                }

            } finally {

                if (
                    networkMigrationJob ===
                    this
                ) {
                    networkMigrationJob = null
                }
            }
        }
    }

    private fun testNetworkMigration() {

        val ip =
            serverIp

        val network =
            activeNetwork

        if (
            shuttingDown
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "TEST_NETWORK_MIGRATION：当前正在关闭"
            )

            return
        }

        if (
            ip.isNullOrBlank()
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "TEST_NETWORK_MIGRATION：服务器IP为空"
            )

            return
        }

        if (
            network == null
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "TEST_NETWORK_MIGRATION：当前Network为空"
            )

            return
        }

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "★★★★ 开始手动UDP迁移测试 ★★★★"
        )

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "测试Network=$network " +
                    "server=$ip:$SERVER_PORT"
        )

        networkMigrationJob?.cancel()

        networkMigrationJob =
            serviceScope.launch {

                try {

                    udpManager.migrate(
                        ip = ip,
                        port = SERVER_PORT,
                        network = network
                    )

                    val newSocket =
                        udpManager.currentSocket()

                    if (
                        newSocket == null ||
                        newSocket.isClosed
                    ) {

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "TEST_NETWORK_MIGRATION：新Socket创建失败"
                        )

                        return@launch
                    }

                    synchronized(
                        connectionLock
                    ) {

                        udpSocket =
                            newSocket
                    }

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "TEST_NETWORK_MIGRATION：★★★★ 新Socket已接管 ★★★★ " +
                                "newPort=${newSocket.localPort}"
                    )

                    repeat(
                        5
                    ) { index ->

                        if (
                            shuttingDown
                        ) {

                            return@launch
                        }

                        sendMessageNow(
                            "$MSG_HELLO:$deviceId"
                        )

                        println(
                            "WALKIE $WALKIE_VERSION: " +
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

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "TEST_NETWORK_MIGRATION：PING已发送"
                    )

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "★★★★ 手动UDP迁移测试完成 ★★★★"
                    )

                } catch (
                    e: Throwable
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "TEST_NETWORK_MIGRATION失败=${e.message}"
                    )

                } finally {

                    if (
                        networkMigrationJob ===
                        this
                    ) {

                        networkMigrationJob =
                            null
                    }
                }
            }
    }

    private fun handleNetworkLost(
        network: Network
    ) {

        if (activeNetwork != network) {
            return
        }

        println(
            "WALKIE $WALKIE_VERSION: 当前网络丢失=$network"
        )

        activeNetwork = null
        isNetworkAvailable = false

        networkMigrationJob?.cancel()
        networkMigrationJob = null

        setConnected(false)

        synchronized(connectionLock) {
            connectionGeneration++
            networkJob?.cancel()
            networkJob = null
        }

        closeSocket()
        clearUserList()

        println(
            "WALKIE $WALKIE_VERSION: 网络断开，旧连接已作废"
        )
    }

    private fun handleNetworkCapabilitiesChanged(
        network: Network,
        capabilities: NetworkCapabilities
    ) {

        if (activeNetwork == network) {
            println(
                "WALKIE $WALKIE_VERSION: 网络能力变化=$capabilities"
            )
        }
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

                if (
                    !isConnected &&
                    !udpManager.isOpen()
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "检测到旧连接任务仍在退出，" +
                                "立即取消旧任务并启动新一代连接"
                    )

                    networkJob?.cancel()
                    networkJob = null

                } else {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "连接任务已存在且连接仍有效，忽略重复连接请求"
                    )

                    return
                }
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

            var connectionSucceeded =
                false

            try {

                connectOnce(
                    ip,
                    generation
                )

                /*
                 * ==================================================
                 * connectOnce() 正常返回。
                 *
                 * 如果此时：
                 *
                 * 1. 当前Manager仍然打开
                 * 2. 当前Socket确实还是本代Socket
                 * 3. connected=true
                 *
                 * 说明可能是正常的迁移接管后继续运行。
                 *
                 * 这里不立即cleanup。
                 * ==================================================
                 */
                connectionSucceeded =
                    udpManager.isOpen() &&
                            isConnected &&
                            isConnectionGenerationCurrent(
                                generation
                            )

                if (
                    connectionSucceeded
                ) {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "connectOnce正常返回，" +
                                "当前连接仍然有效 " +
                                "generation=$generation"
                    )

                    reconnectDelay =
                        INITIAL_RECONNECT_INTERVAL

                    /*
                     * 给connectOnce/Manager一次短暂恢复检查机会。
                     *
                     * 不再无限continue。
                     */
                    delay(
                        SOCKET_RECEIVE_TIMEOUT.toLong()
                    )
                }

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

            /*
             * ==================================================
             * 生命周期检查
             * ==================================================
             */
            if (
                !serviceScope.isActive ||
                shuttingDown ||
                !isConnectionGenerationCurrent(
                    generation
                )
            ) {

                break
            }

            /*
             * ==================================================
             * 关键修复
             *
             * 不再简单根据：
             *
             * udpManager.isOpen()
             *
             * 判断“连接正常”。
             *
             * Socket open != 网络正常。
             *
             * 真正断网后，Android 可能仍然保持
             * DatagramSocket open。
             *
             * 所以发生异常以后：
             *
             * 必须进入 cleanupConnection()
             *      ↓
             * 关闭失效Socket
             *      ↓
             * 等待
             *      ↓
             * 重新创建Socket
             *
             * 这样才能真正恢复。
             * ==================================================
             */

            if (
                !connectionSucceeded
            ) {

                cleanupConnection(
                    generation
                )
            }

            /*
             * ==================================================
             * 无网络：
             *
             * 不立即疯狂重连。
             * 等待NetworkCallback通知新网络。
             * ==================================================
             */
            if (
                !isNetworkAvailable
            ) {

                delay(
                    1000L
                )

                continue
            }

            /*
             * ==================================================
             * 如果网络已经恢复，
             * 但当前Manager没有有效Socket，
             * 继续进入自动重连。
             * ==================================================
             */
            if (
                !udpManager.isOpen()
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "UDP Manager无有效Socket，" +
                            "准备自动重连 " +
                            "generation=$generation"
                )

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

                continue
            }

            /*
             * ==================================================
             * 当前连接仍然有效。
             *
             * 重连等待时间重置。
             * ==================================================
             */
            reconnectDelay =
                INITIAL_RECONNECT_INTERVAL

            delay(
                SOCKET_RECEIVE_TIMEOUT.toLong()
            )
        }
    }

    /*
     * ============================================================
     * UDP连接
     * ============================================================
     */

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

        /*
         * ========================================================
         * V24.9.2
         *
         * UDP Socket 的创建统一交给 WalkieUdpManager。
         *
         * Manager负责：
         *
         * 创建Socket
         *      ↓
         * 绑定当前Network
         *      ↓
         * 持有当前Socket
         *      ↓
         * 网络切换时执行无缝迁移
         * ========================================================
         */

        val network =
            activeNetwork

        udpManager.open(
            ip = ip,
            port = SERVER_PORT,
            network = network
        )

        /*
         * Manager创建成功以后，
         * 取出当前Socket。
         *
         * 注意：
         *
         * 这里使用var。
         *
         * 因为网络切换以后，
         * 当前接收循环需要：
         *
         * 旧Socket
         *      ↓
         * 新Socket
         *
         * 无缝切换。
         */
        val firstSocket =
            udpManager.currentSocket()
                ?: run {

                    println(
                        "WALKIE $WALKIE_VERSION: " +
                                "UDP Manager创建Socket失败"
                    )

                    return
                }

        var socket: DatagramSocket =
            firstSocket

        if (
            socket.isClosed
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "UDP Manager创建Socket失败"
            )

            return
        }

        /*
         * 再次检查generation。
         *
         * 防止：
         *
         * 创建完Socket以后，
         * 网络已经发生新的变化。
         */
        if (
            !isConnectionGenerationCurrent(
                generation
            ) ||
            shuttingDown
        ) {

            try {
                udpManager.close()
            } catch (_: Exception) {
            }

            return
        }

        /*
         * ========================================================
         * 与原代码保持一致：
         *
         * Socket参数继续在Service侧保持。
         * ========================================================
         */

        try {

            socket.soTimeout =
                SOCKET_RECEIVE_TIMEOUT

        } catch (_: Exception) {
        }

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
         * 记录到旧字段。
         *
         * 当前业务其他地方仍然可以继续使用：
         *
         * udpSocket
         * serverAddress
         * ========================================================
         */
        synchronized(
            connectionLock
        ) {

            if (
                connectionGeneration !=
                generation ||
                shuttingDown
            ) {

                try {
                    udpManager.close()
                } catch (_: Exception) {
                }

                return
            }

            udpSocket =
                socket
        }

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "UDP Manager创建Socket成功 " +
                    "localPort=${socket.localPort} " +
                    "generation=$generation"
        )

        try {

            /*
             * ====================================================
             * V24.9.1
             *
             * AudioTrack不再由WalkieService直接创建。
             *
             * 连接建立时不提前初始化播放器。
             * ====================================================
             */

            /*
 * 网络重新建立后：
 *
 * HELLO 连续发送3次。
 *
 * 目的：
 *
 * 1. 提高网络刚恢复时首个UDP控制包到达率
 * 2. 尽快让VPS重新确认当前UDP端点
 * 3. 不等待第一次HELLO的结果再重试
 *
 * 三次之间只间隔40ms，
 * 不会增加明显恢复延迟。
 */
            repeat(3) { index ->

                if (
                    !isConnectionGenerationCurrent(
                        generation
                    ) ||
                    shuttingDown
                ) {

                    return
                }

                sendMessageNow(
                    "$MSG_HELLO:$deviceId:$WALKIE_VERSION"
                )

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "重连HELLO ${index + 1}/3 " +
                            "generation=$generation"
                )

                if (
                    index < 2
                ) {

                    delay(
                        40L
                    )
                }
            }

            val now =
                System.currentTimeMillis()

            resetNetworkStats()

            lastNetworkPingTime =
                now

            lastKeepAliveTime =
                now

            lastServerActivityTime =
                now

            val buffer =
                ByteArray(
                    4096
                )

            /*
             * ====================================================
             * V24.9.2
             *
             * 关键修改：
             *
             * 这里不能再使用：
             *
             * !socket.isClosed
             *
             * 因为网络迁移时：
             *
             * 旧Socket会正常关闭
             *
             * 但Manager已经有新Socket。
             *
             * 所以连接循环应该判断：
             *
             * Manager当前是否还有有效Socket。
             * ====================================================
             */
            while (
                serviceScope.isActive &&
                !shuttingDown &&
                udpManager.isOpen() &&
                isConnectionGenerationCurrent(
                    generation
                )
            ) {

                /*
                 * =================================================
                 * V24.9.2 网络迁移接管检测
                 *
                 * 如果：
                 *
                 * 旧Socket
                 *      ↓
                 * Manager切换
                 *      ↓
                 * 新Socket
                 *
                 * 那么这里把本地socket引用
                 * 同步到新的当前Socket。
                 *
                 * 这样：
                 *
                 * 后面的日志
                 * KEEPALIVE
                 * 连接状态
                 * finally判断
                 *
                 * 都能够正确对应新Socket。
                 * =================================================
                 */
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
                    currentManagerSocket !== socket
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

                expireNetworkPings(
                    currentTime
                )

                if (
                    isConnected &&
                    currentTime -
                    lastNetworkPingTime >=
                    NETWORK_PING_INTERVAL
                ) {

                    sendNetworkPing(
                        currentTime
                    )

                    lastNetworkPingTime =
                        currentTime
                }

                updateNetworkBitrate(
                    currentTime
                )

                /*
                 * KEEPALIVE。
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

                    /*
                     * 接收统一由Manager处理。
                     *
                     * Manager每次都会使用
                     * 当前activeSocket。
                     */
                    udpManager.receive(
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

                        handleNetworkPong(
                            text
                        )

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
                             * 必须在开始录音之前播放。
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
                     *
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

                    if (
                        opusPayload.isEmpty()
                    ) {

                        println(
                            "WALKIE AUDIO: drop empty Opus payload"
                        )

                        continue
                    }

                    if (
                        opusPayload.size >
                        MAX_OPUS_PACKET_SIZE
                    ) {

                        println(
                            "WALKIE AUDIO: drop oversized Opus payload=${opusPayload.size}"
                        )

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

                            audioPlayback.requestRecovery()

                            println(
                                "WALKIE AUDIO: " +
                                        "连续Opus解码失败=" +
                                        consecutiveDecodeFailures +
                                        "，请求播放恢复"
                            )
                        }

                        continue
                    }

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

                    if (
                        pcmData.size < 80 ||
                        pcmData.size >
                        SAMPLE_RATE / 5
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

                    /*
                     * 正常超时。
                     *
                     * 下一轮循环会重新检查
                     * Manager当前Socket。
                     */
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
                        currentManagerSocket !== socket &&
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
                currentSocket === socket
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
                        udpSocket === socket
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

                        try {

                            val framedAudio =
                                buildV231AudioPacket(
                                    opus
                                )


                            udpManager.send(
                                framedAudio
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

        networkStats.reset(
            System.currentTimeMillis()
        )
    }

    private fun sendNetworkPing(
        now: Long
    ) {

        networkStats.sendPing(now)
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

        networkStats.expirePings(now)
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

        networkStats.recordAudioTransmit(bytes)
    }

    private fun recordAudioReceive(
        bytes: Int
    ) {

        networkStats.recordAudioReceive(bytes)
    }

    private fun updateNetworkBitrate(
        now: Long
    ) {

        networkStats.updateBitrate(now)
    }

    private fun broadcastNetworkStatus(
        force: Boolean
    ) {

        networkStats.broadcastStatus(force)
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
         * ============================================================
         * V24.9.1
         * PCM进入独立音频播放模块。
         *
         * WalkieService：
         *      负责 UDP / W23A / Opus 解码
         *
         * WalkieAudioPlayback：
         *      负责播放队列 / 缓冲 / 播放线程
         *
         * WalkieAudioPlayer：
         *      负责 AudioTrack 生命周期
         * ============================================================
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

        try {

            audioPlayback.enqueue(
                data
            )

        } catch (
            throwable: Throwable
        ) {

            println(
                "WALKIE AUDIO: " +
                        "独立播放模块加入PCM失败: " +
                        throwable.message
            )
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
         * ============================================================
         * 旧generation没有权限清理当前状态。
         * ============================================================
         */
        if (
            !isConnectionGenerationCurrent(
                generation
            )
        ) {

            return
        }

        /*
         * ============================================================
         * 保存当前频道。
         *
         * 真正断线以后重新连接，
         * 需要恢复原来的频道。
         * ============================================================
         */
        if (
            currentChannel.isNotBlank() &&
            currentChannel !=
            "public"
        ) {

            reconnectChannel =
                currentChannel
        }

        /*
         * ============================================================
         * 停止当前讲话状态
         * ============================================================
         */
        talkRequesting =
            false

        talkAllowed =
            false

        isSpeaking =
            false

        stopRecording()

        /*
         * ============================================================
         * 关闭已经失效的UDP连接
         * ============================================================
         */
        closeSocket()

        serverAddress =
            null

        /*
         * ============================================================
         * V24.9.1关键修复：
         *
         * 真正发生“断线 -> 自动重连”时，
         * V23.1音频接收状态必须全部重新开始。
         *
         * 否则：
         *
         * 断线前：
         * expectedSequence = 1000
         *
         * 重连后：
         * 第一个包可能已经是1200
         *
         * 重排器会认为1200是未来包，
         * 一直等待旧的1001，
         * 最终导致：
         *
         * UDP收到了音频
         *       ↓
         * W23A重排卡住
         *       ↓
         * 不输出opusPayload
         *       ↓
         * 没有声音
         *
         * 手动完全重连之所以能恢复，
         * 就是因为Service重新初始化了这些状态。
         *
         * 这里直接模拟同样的“音频接收状态重建”。
         * ============================================================
         */
        resetV231AudioJitter()

        /*
         * 连续解码失败计数也必须清零。
         */
        consecutiveDecodeFailures =
            0

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "V23.1音频接收状态已重置，" +
                    "等待自动重连后的新音频流"
        )

        /*
         * ============================================================
         * 清理断线前残留的旧语音。
         *
         * 这些PCM已经没有实时意义。
         * ============================================================
         */
        audioPlayback.clearQueue()

        /*
         * 下一次真正收到新语音以后，
         * 重新按照弱网恢复缓冲策略开始播放。
         */
        audioPlayback.requestRecovery()

        /*
         * ============================================================
         * 断线以后保留原频道。
         *
         * 只有没有恢复频道时才回到public。
         * ============================================================
         */
        if (
            reconnectChannel.isBlank() ||
            reconnectChannel ==
            "public"
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

        try {
            udpManager.close()
        } catch (e: Throwable) {
            println(
                "WALKIE $WALKIE_VERSION: " +
                        "关闭UDP Manager异常=${e.message}"
            )
        }

        synchronized(
            connectionLock
        ) {
            udpSocket =
                null
        }

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "UDP Manager 已关闭"
        )
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

        audioPlayback.release()

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