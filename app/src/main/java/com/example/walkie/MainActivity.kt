package com.example.walkie

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.walkie.ui.theme.WalkieTheme

private const val WALKIE_VERSION = "V24.9.1"

private const val DEFAULT_SERVER_IP =
    "38.146.29.169"

private const val UI_PREFS =
    "walkie_session_v23_3"

private const val UI_PREF_NICKNAME =
    "nickname"

data class ChannelUiInfo(
    val name: String,
    val onlineCount: Int = 0,
    val isPrivate: Boolean = false,
    val requirePassword: Boolean = false
)

data class OnlineUserUiInfo(
    val userId: String,
    val nickname: String
)

class MainActivity : ComponentActivity() {

    /*
     * ============================================================
     * UI 状态
     * ============================================================
     */

    private var connectionState by mutableStateOf(
        false
    )

    private var talkStatus by mutableStateOf(
        WalkieService.TALK_STATUS_NONE
    )

    private var currentChannel by mutableStateOf(
        "public"
    )

    private var currentOnlineCount by mutableStateOf(
        0
    )

    private var currentPrivate by mutableStateOf(
        false
    )

    private var nickname by mutableStateOf(
        ""
    )

    private var myUserId by mutableStateOf(
        ""
    )

    private var onlineUsers by mutableStateOf(
        emptyList<OnlineUserUiInfo>()
    )

    private var channelList by mutableStateOf(
        listOf(
            ChannelUiInfo(
                name = "public"
            )
        )
    )

    private var channelMessage by mutableStateOf(
        ""
    )

    private var nicknameDialog by mutableStateOf(
        false
    )

    private var nicknameInput by mutableStateOf(
        ""
    )

    private var createChannelDialog by mutableStateOf(
        false
    )

    private var deleteChannelDialog by mutableStateOf(
        false
    )

    private var joinPasswordDialog by mutableStateOf(
        false
    )

    private var joinPasswordChannel by mutableStateOf(
        ""
    )

    private var joinPassword by mutableStateOf(
        ""
    )

    /*
     * ============================================================
     * 网络质量
     * ============================================================
     */

    private var networkBaseType by mutableStateOf(
        "检测中"
    )

    private var networkType by mutableStateOf(
        "检测中"
    )

    private var networkLatency by mutableStateOf(
        -1L
    )

    private var networkLoss by mutableStateOf(
        100f
    )

    private var networkQuality by mutableStateOf(
        "检测中"
    )

    private var networkBitrate by mutableStateOf(
        0f
    )

    private var networkUploadBitrate by mutableStateOf(
        0f
    )

    private var networkDownloadBitrate by mutableStateOf(
        0f
    )

    private var networkJitter by mutableStateOf(
        -1L
    )

    /*
     * 当前页面
     */
    private var currentPage by mutableStateOf(
        "home"
    )

    /*
     * ============================================================
     * 系统返回
     * ============================================================
     */

    private val systemBackCallback =
        object : OnBackPressedCallback(true) {

            override fun handleOnBackPressed() {

                if (
                    currentPage !=
                    "home"
                ) {

                    currentPage =
                        "home"

                    return
                }

                isEnabled =
                    false

                onBackPressedDispatcher
                    .onBackPressed()

                isEnabled =
                    true
            }
        }

    /*
     * ============================================================
     * 权限
     * ============================================================
     */

    private val requestMicrophone =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            println(
                "WALKIE $WALKIE_VERSION: 麦克风权限=$granted"
            )

            if (
                granted
            ) {

                requestNotificationPermissionIfNeeded()

            } else {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "麦克风权限未授权"
                )
            }
        }

    private val requestNotification =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "通知权限申请完成"
            )

            requestLocationPermissionIfNeeded()
        }

    /*
     * GPS 定位权限申请器
     *
     * 同时申请：
     * ACCESS_FINE_LOCATION
     * ACCESS_COARSE_LOCATION
     */
    private val requestLocation =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true

            val coarseGranted =
                permissions[
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ] == true

            val granted =
                fineGranted ||
                        coarseGranted

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "定位权限结果=" +
                        "fine=$fineGranted " +
                        "coarse=$coarseGranted"
            )

            if (
                granted
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "GPS定位权限已获得"
                )

            } else {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "GPS定位权限未获得"
                )
            }
        }

    /*
     * ============================================================
     * BroadcastReceiver
     * ============================================================
     */

    private val receiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (
                    intent?.action
                ) {

                    /*
                     * ==================================================
                     * 连接状态
                     * ==================================================
                     */

                    WalkieService.ACTION_CONNECTION_STATUS -> {

                        val connected =
                            intent.getBooleanExtra(
                                WalkieService.EXTRA_CONNECTED,
                                false
                            )

                        connectionState =
                            connected

                        if (
                            !connected
                        ) {

                            onlineUsers =
                                emptyList()

                            currentOnlineCount =
                                0

                            talkStatus =
                                WalkieService.TALK_STATUS_NONE

                            myUserId =
                                ""

                            networkLatency =
                                -1L

                            networkLoss =
                                100f

                            networkQuality =
                                "离线"

                            networkBitrate =
                                0f

                            networkUploadBitrate =
                                0f

                            networkDownloadBitrate =
                                0f

                            networkJitter =
                                -1L

                            networkType =
                                if (
                                    networkBaseType ==
                                    "无网络"
                                ) {

                                    "无网络 · 离线"

                                } else {

                                    "$networkBaseType · 离线"
                                }

                        } else {

                            refreshNetworkDisplay()
                        }

                        updateNetworkType()

                        println(
                            "WALKIE $WALKIE_VERSION: 连接状态=$connected"
                        )
                    }

                    /*
                     * ==================================================
                     * 抢麦
                     * ==================================================
                     */

                    WalkieService.ACTION_TALK_STATUS -> {

                        talkStatus =
                            intent.getStringExtra(
                                WalkieService.EXTRA_TALK_STATUS
                            )
                                ?: WalkieService.TALK_STATUS_NONE

                        println(
                            "WALKIE $WALKIE_VERSION: TALK STATUS=$talkStatus"
                        )
                    }

                    /*
                     * ==================================================
                     * 我的用户信息
                     * ==================================================
                     */

                    WalkieService.ACTION_MY_USER_INFO -> {

                        myUserId =
                            intent.getStringExtra(
                                WalkieService.EXTRA_MY_USER_ID
                            )
                                ?: myUserId

                        val serverNickname =
                            intent.getStringExtra(
                                WalkieService.EXTRA_MY_USERNAME
                            )
                                ?: ""

                        if (
                            serverNickname.isNotBlank() &&
                            !serverNickname.startsWith(
                                "USER-"
                            )
                        ) {

                            nickname =
                                serverNickname
                        }

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "我的用户ID=$myUserId " +
                                    "昵称=$nickname"
                        )
                    }

                    /*
                     * ==================================================
                     * 当前频道在线人员
                     * ==================================================
                     */

                    WalkieService.ACTION_USER_LIST -> {

                        val items =
                            intent.getStringArrayListExtra(
                                WalkieService.EXTRA_USER_LIST
                            )
                                .orEmpty()

                        val result =
                            items
                                .mapNotNull {
                                    parseUser(it)
                                }
                                .distinctBy {
                                    it.userId
                                }

                        onlineUsers =
                            result

                        currentOnlineCount =
                            result.size

                        connectionState =
                            true

                        updateNetworkType()

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "在线人员=${result.size}"
                        )
                    }

                    /*
                     * ==================================================
                     * 频道列表
                     * ==================================================
                     */

                    WalkieService.ACTION_CHANNEL_LIST -> {

                        val infos =
                            intent.getStringArrayListExtra(
                                WalkieService.EXTRA_CHANNEL_INFO
                            )
                                .orEmpty()

                        val parsed =
                            infos
                                .mapNotNull {
                                    parseChannel(it)
                                }
                                .distinctBy {
                                    it.name
                                }
                                .sortedBy {
                                    it.name
                                }

                        if (
                            parsed.isNotEmpty()
                        ) {

                            channelList =
                                parsed

                            connectionState =
                                true

                            updateNetworkType()
                        }

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "频道列表=$parsed"
                        )
                    }

                    /*
                     * ==================================================
                     * 网络质量
                     * ==================================================
                     */

                    WalkieService.ACTION_NETWORK_STATUS -> {

                        networkLatency =
                            intent.getLongExtra(
                                WalkieService.EXTRA_NETWORK_LATENCY,
                                -1L
                            )

                        networkLoss =
                            intent.getFloatExtra(
                                WalkieService.EXTRA_NETWORK_LOSS,
                                100f
                            )
                                .coerceIn(
                                    0f,
                                    100f
                                )

                        networkQuality =
                            intent.getStringExtra(
                                WalkieService.EXTRA_NETWORK_QUALITY
                            )
                                ?: "检测中"

                        networkBitrate =
                            intent.getFloatExtra(
                                WalkieService.EXTRA_NETWORK_BITRATE,
                                0f
                            )
                                .coerceAtLeast(
                                    0f
                                )

                        networkUploadBitrate =
                            intent.getFloatExtra(
                                WalkieService.EXTRA_NETWORK_UPLOAD_BITRATE,
                                0f
                            )
                                .coerceAtLeast(
                                    0f
                                )

                        networkDownloadBitrate =
                            intent.getFloatExtra(
                                WalkieService.EXTRA_NETWORK_DOWNLOAD_BITRATE,
                                0f
                            )
                                .coerceAtLeast(
                                    0f
                                )

                        networkJitter =
                            intent.getLongExtra(
                                WalkieService.EXTRA_NETWORK_JITTER,
                                -1L
                            )
                                .coerceAtLeast(
                                    0L
                                )

                        updateNetworkType()

                        refreshNetworkDisplay()

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "网络质量=$networkQuality " +
                                    "延迟=${networkLatency}ms " +
                                    "丢包=${networkLoss}% " +
                                    "抖动=${networkJitter}ms " +
                                    "上行=${networkUploadBitrate}kbps " +
                                    "下行=${networkDownloadBitrate}kbps"
                        )
                    }

                    /*
                     * ==================================================
                     * 当前频道状态
                     * ==================================================
                     */

                    WalkieService.ACTION_CHANNEL_STATUS -> {

                        currentChannel =
                            intent.getStringExtra(
                                WalkieService.EXTRA_CURRENT_CHANNEL
                            )
                                ?: currentChannel

                        currentPrivate =
                            intent.getBooleanExtra(
                                WalkieService.EXTRA_CHANNEL_PRIVATE,
                                currentPrivate
                            )

                        channelMessage =
                            intent.getStringExtra(
                                WalkieService.EXTRA_CHANNEL_MESSAGE
                            )
                                ?: ""

                        updateNetworkType()

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "频道=$currentChannel " +
                                    "人数=$currentOnlineCount"
                        )
                    }

                    /*
                     * ==================================================
                     * 删除频道
                     * ==================================================
                     */

                    WalkieService.ACTION_CHANNEL_DELETED -> {

                        val deleted =
                            intent.getStringExtra(
                                WalkieService.EXTRA_DELETED_CHANNEL
                            )

                        if (
                            deleted ==
                            currentChannel
                        ) {

                            currentChannel =
                                "public"

                            currentOnlineCount =
                                0

                            currentPrivate =
                                false

                            onlineUsers =
                                emptyList()
                        }

                        channelMessage =
                            "频道已删除：${deleted ?: ""}"
                    }
                }
            }
        }

    /*
     * ============================================================
     * onCreate
     * ============================================================
     */

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        onBackPressedDispatcher.addCallback(
            this,
            systemBackCallback
        )

        nickname =
            getSharedPreferences(
                UI_PREFS,
                MODE_PRIVATE
            )
                .getString(
                    UI_PREF_NICKNAME,
                    ""
                )
                ?.trim()
                ?.take(20)
                ?: ""

        nicknameInput =
            nickname

        nicknameDialog =
            nickname.isBlank()

        updateNetworkType()

        requestPermissionsIfNeeded()

        startFloatingPtt()

        val filter =
            IntentFilter().apply {

                addAction(
                    WalkieService.ACTION_CONNECTION_STATUS
                )

                addAction(
                    WalkieService.ACTION_TALK_STATUS
                )

                addAction(
                    WalkieService.ACTION_MY_USER_INFO
                )

                addAction(
                    WalkieService.ACTION_USER_LIST
                )

                addAction(
                    WalkieService.ACTION_NETWORK_STATUS
                )

                addAction(
                    WalkieService.ACTION_CHANNEL_LIST
                )

                addAction(
                    WalkieService.ACTION_CHANNEL_STATUS
                )

                addAction(
                    WalkieService.ACTION_CHANNEL_DELETED
                )
            }

        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {

            WalkieTheme {

                Surface(

                    modifier =
                        Modifier.fillMaxSize(),

                    color =
                        MaterialTheme
                            .colorScheme
                            .background
                ) {

                    WalkieV20Screen(

                        connected =
                            connectionState,

                        talkStatus =
                            talkStatus,

                        nickname =
                            nickname,

                        myUserId =
                            myUserId,

                        currentChannel =
                            currentChannel,

                        currentOnlineCount =
                            currentOnlineCount,

                        currentPrivate =
                            currentPrivate,

                        channels =
                            channelList,

                        onlineUsers =
                            onlineUsers,

                        networkType =
                            networkType,

                        page =
                            currentPage,

                        nicknameDialog =
                            nicknameDialog,

                        nicknameInput =
                            nicknameInput,

                        createChannelDialog =
                            createChannelDialog,

                        deleteChannelDialog =
                            deleteChannelDialog,

                        joinPasswordDialog =
                            joinPasswordDialog,

                        joinPasswordChannel =
                            joinPasswordChannel,

                        joinPassword =
                            joinPassword,

                        onPageChanged = {

                            currentPage =
                                it
                        },

                        onConnect = {

                            connectServer()
                        },

                        onDisconnect = {

                            disconnectServer()
                        },

                        onOpenNickname = {

                            nicknameInput =
                                nickname

                            nicknameDialog =
                                true
                        },

                        onDismissNickname = {

                            if (
                                nickname.isNotBlank()
                            ) {

                                nicknameDialog =
                                    false
                            }
                        },

                        onNicknameChanged = {

                            nicknameInput =
                                it.take(20)
                        },

                        onSaveNickname = {

                            saveNickname()
                        },

                        onRefresh = {

                            requestChannelList()
                        },

                        onSelectChannel = {

                            selectChannel(it)
                        },

                        onOpenCreate = {

                            createChannelDialog =
                                true
                        },

                        onDismissCreate = {

                            createChannelDialog =
                                false
                        },

                        onCreateChannel = {
                                name,
                                privateChannel,
                                password ->

                            createChannelDialog =
                                false

                            createChannel(
                                name,
                                privateChannel,
                                password
                            )
                        },

                        onOpenDelete = {

                            if (
                                currentChannel !=
                                "public"
                            ) {

                                deleteChannelDialog =
                                    true
                            }
                        },

                        onDismissDelete = {

                            deleteChannelDialog =
                                false
                        },

                        onConfirmDelete = {

                            deleteChannelDialog =
                                false

                            deleteCurrentChannel()
                        },

                        onDismissPassword = {

                            joinPasswordDialog =
                                false

                            joinPasswordChannel =
                                ""

                            joinPassword =
                                ""
                        },

                        onPasswordChanged = {

                            joinPassword =
                                it
                        },

                        onConfirmPassword = {

                            val channel =
                                joinPasswordChannel

                            val password =
                                joinPassword

                            if (
                                channel.isNotBlank() &&
                                password.isNotBlank()
                            ) {

                                joinPasswordDialog =
                                    false

                                joinPasswordChannel =
                                    ""

                                joinPassword =
                                    ""

                                sendJoin(
                                    channel,
                                    password
                                )
                            }
                        },

                        onStartSpeaking = {

                            println(
                                "WALKIE UI: ★触发一次 START★"
                            )

                            startSpeaking()
                        },

                        onStopSpeaking = {

                            stopSpeaking()
                        }
                    )
                }
            }
        }
    }

    /*
     * ============================================================
     * onResume
     * ============================================================
     */

    override fun onResume() {

        super.onResume()

        startFloatingPtt()

        updateNetworkType()

        requestChannelList()

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "Activity 回到前台，主动请求 Service 同步状态"
        )
    }

    /*
     * ============================================================
     * 全局悬浮 PTT
     * ============================================================
     */

    private fun startFloatingPtt() {

        if (
            !android.provider.Settings.canDrawOverlays(
                this
            )
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "没有悬浮窗权限，打开系统授权页面"
            )

            try {

                val intent =
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                    ).apply {

                        data =
                            android.net.Uri.parse(
                                "package:$packageName"
                            )
                    }

                startActivity(
                    intent
                )

            } catch (
                error: Exception
            ) {

                println(
                    "WALKIE $WALKIE_VERSION: " +
                            "打开悬浮窗权限页面失败=" +
                            error.message
                )
            }

            return
        }

        try {

            val intent =
                Intent(
                    this,
                    WalkieFloatingPttService::class.java
                ).apply {

                    action =
                        WalkieFloatingPttService.ACTION_SHOW
                }

            startService(
                intent
            )

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "悬浮 PTT 已启动"
            )

        } catch (
            error: Exception
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "启动悬浮 PTT 失败=" +
                        error.message
            )
        }
    }

    /*
     * ============================================================
     * 权限
     * ============================================================
     */

    private fun requestPermissionsIfNeeded() {

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            requestMicrophone.launch(
                Manifest.permission.RECORD_AUDIO
            )

        } else {

            requestNotificationPermissionIfNeeded()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {

                requestNotification.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )

            } else {

                requestLocationPermissionIfNeeded()
            }

        } else {

            requestLocationPermissionIfNeeded()
        }
    }

    /*
     * ============================================================
     * GPS 定位权限
     * ============================================================
     */

    private fun requestLocationPermissionIfNeeded() {

        val fineGranted =
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (
            fineGranted ||
            coarseGranted
        ) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "GPS定位权限已经存在"
            )

            return
        }

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "开始申请GPS定位权限"
        )

        requestLocation.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    /*
     * ============================================================
     * 网络类型
     * ============================================================
     */

    private fun updateNetworkType() {

        try {

            val connectivityManager =
                getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            val network =
                connectivityManager.activeNetwork

            val capabilities =
                network?.let {

                    connectivityManager
                        .getNetworkCapabilities(
                            it
                        )
                }

            networkBaseType =
                when {

                    capabilities == null ->
                        "无网络"

                    capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) ->
                        "Wi-Fi"

                    capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_CELLULAR
                    ) ->
                        "移动数据"

                    capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_ETHERNET
                    ) ->
                        "有线网络"

                    else ->
                        "网络已连接"
                }

            if (
                connectionState ||
                networkQuality != "检测中"
            ) {

                refreshNetworkDisplay()

            } else {

                networkType =
                    networkBaseType
            }

        } catch (_: Exception) {

            networkBaseType =
                "未知"

            if (
                networkQuality != "检测中"
            ) {

                refreshNetworkDisplay()

            } else {

                networkType =
                    networkBaseType
            }
        }
    }

    /*
     * ============================================================
     * 网络 UI 文本
     * ============================================================
     */

    private fun refreshNetworkDisplay() {

        val latencyText =
            if (
                networkLatency >= 0L
            ) {

                "${networkLatency}ms"

            } else {

                "--"
            }

        val lossText =
            String.format(
                "%.1f%%",
                networkLoss
            )

        networkType =
            "$networkBaseType · " +
                    "$networkQuality · " +
                    "$latencyText · " +
                    "丢包 $lossText"
    }

    /*
     * ============================================================
     * 连接
     * ============================================================
     */

    private fun connectServer() {

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_START

                putExtra(
                    WalkieService.EXTRA_SERVER_IP,
                    DEFAULT_SERVER_IP
                )
            }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                startForegroundService(
                    intent
                )

            } else {

                @Suppress("DEPRECATION")
                startService(
                    intent
                )
            }

            println(
                "WALKIE $WALKIE_VERSION: 请求连接兄弟服务器"
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: 连接失败=${e.message}"
            )
        }
    }

    /*
     * ============================================================
     * 断开
     * ============================================================
     */

    private fun disconnectServer() {

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_STOP
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * 昵称
     * ============================================================
     */

    private fun saveNickname() {

        val value =
            nicknameInput
                .trim()
                .take(20)

        if (
            value.isBlank()
        ) {

            return
        }

        nickname =
            value

        nicknameInput =
            value

        getSharedPreferences(
            UI_PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                UI_PREF_NICKNAME,
                value
            )
            .apply()

        nicknameDialog =
            false

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_SET_NICKNAME

                putExtra(
                    "nickname",
                    value
                )
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "发送昵称失败=${e.message}"
            )
        }

        println(
            "WALKIE $WALKIE_VERSION: 保存昵称=$value"
        )
    }

    /*
     * ============================================================
     * 频道列表
     * ============================================================
     */

    private fun requestChannelList() {

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_CHANNEL_LIST
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "请求频道列表失败=${e.message}"
            )
        }
    }

    /*
     * ============================================================
     * 切换频道
     * ============================================================
     */

    private fun selectChannel(
        channel: ChannelUiInfo
    ) {

        if (
            !connectionState
        ) {

            return
        }

        if (
            channel.name ==
            currentChannel
        ) {

            return
        }

        onlineUsers =
            emptyList()

        currentOnlineCount =
            0

        WalkieLocationUiStore
            .clear()

        if (
            channel.isPrivate ||
            channel.requirePassword
        ) {

            joinPasswordChannel =
                channel.name

            joinPassword =
                ""

            joinPasswordDialog =
                true

        } else {

            sendJoin(
                channel.name,
                ""
            )
        }
    }

    private fun sendJoin(
        channel: String,
        password: String
    ) {

        if (
            !connectionState
        ) {

            return
        }

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_JOIN_CHANNEL

                putExtra(
                    WalkieService.EXTRA_CHANNEL_NAME,
                    channel
                )

                putExtra(
                    WalkieService.EXTRA_CHANNEL_PASSWORD,
                    password
                )
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * 创建频道
     * ============================================================
     */

    private fun createChannel(
        name: String,
        privateChannel: Boolean,
        password: String
    ) {

        if (
            !connectionState
        ) {

            return
        }

        val cleanName =
            name.trim()

        if (
            cleanName.isBlank()
        ) {

            return
        }

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_CREATE_CHANNEL

                putExtra(
                    WalkieService.EXTRA_CHANNEL_NAME,
                    cleanName
                )

                putExtra(
                    WalkieService.EXTRA_CHANNEL_PASSWORD,
                    password.trim()
                )

                putExtra(
                    WalkieService.EXTRA_CHANNEL_PRIVATE,
                    privateChannel
                )
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * 删除频道
     * ============================================================
     */

    private fun deleteCurrentChannel() {

        if (
            !connectionState ||
            currentChannel ==
            "public"
        ) {

            return
        }

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_DELETE_CHANNEL

                putExtra(
                    WalkieService.EXTRA_CHANNEL_NAME,
                    currentChannel
                )
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * PTT 开始
     * ============================================================
     */

    private fun startSpeaking() {

        if (
            !connectionState
        ) {

            return
        }

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_SPEAK_START
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * PTT 停止
     * ============================================================
     */

    private fun stopSpeaking() {

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_SPEAK_STOP
            }

        try {

            @Suppress("DEPRECATION")
            startService(
                intent
            )

        } catch (_: Exception) {
        }
    }

    /*
     * ============================================================
     * 在线用户解析
     * ============================================================
     */

    private fun parseUser(
        value: String
    ): OnlineUserUiInfo? {

        val separator =
            value.indexOf('|')

        if (
            separator <= 0
        ) {

            return null
        }

        val userId =
            value
                .substring(
                    0,
                    separator
                )
                .trim()

        val username =
            value
                .substring(
                    separator + 1
                )
                .trim()
                .ifBlank {
                    "未命名用户"
                }

        if (
            userId.isBlank()
        ) {

            return null
        }

        return OnlineUserUiInfo(
            userId =
                userId,

            nickname =
                username
        )
    }

    /*
     * ============================================================
     * 频道解析
     * ============================================================
     */

    private fun parseChannel(
        value: String
    ): ChannelUiInfo? {

        val parts =
            value.split(",")

        val name =
            parts
                .getOrNull(0)
                ?.trim()
                .orEmpty()

        if (
            name.isBlank()
        ) {

            return null
        }

        val type =
            parts
                .getOrNull(1)
                ?.trim()
                ?.uppercase()
                ?: "PUBLIC"

        val privateChannel =
            type ==
                    "PRIVATE"

        val count =
            parts
                .getOrNull(2)
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0

        return ChannelUiInfo(

            name =
                name,

            onlineCount =
                count,

            isPrivate =
                privateChannel,

            requirePassword =
                privateChannel
        )
    }

    override fun onDestroy() {

        try {

            unregisterReceiver(
                receiver
            )

        } catch (_: Exception) {
        }

        systemBackCallback.remove()

        super.onDestroy()
    }
}

/*
 * ================================================================
 * V24.9.1 SCREEN
 * ================================================================
 */

@Composable
private fun WalkieV20Screen(
    connected: Boolean,
    talkStatus: String,
    nickname: String,
    myUserId: String,
    currentChannel: String,
    currentOnlineCount: Int,
    currentPrivate: Boolean,
    channels: List<ChannelUiInfo>,
    onlineUsers: List<OnlineUserUiInfo>,
    networkType: String,
    page: String,
    nicknameDialog: Boolean,
    nicknameInput: String,
    createChannelDialog: Boolean,
    deleteChannelDialog: Boolean,
    joinPasswordDialog: Boolean,
    joinPasswordChannel: String,
    joinPassword: String,
    onPageChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNickname: () -> Unit,
    onDismissNickname: () -> Unit,
    onNicknameChanged: (String) -> Unit,
    onSaveNickname: () -> Unit,
    onRefresh: () -> Unit,
    onSelectChannel: (ChannelUiInfo) -> Unit,
    onOpenCreate: () -> Unit,
    onDismissCreate: () -> Unit,
    onCreateChannel: (
        String,
        Boolean,
        String
    ) -> Unit,
    onOpenDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissPassword: () -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConfirmPassword: () -> Unit,
    onStartSpeaking: () -> Unit,
    onStopSpeaking: () -> Unit
) {

    /*
     * ============================================================
     * 距离 UI 状态
     * ============================================================
     */

    val memberDistancesMeters by
    WalkieLocationUiStore
        .memberDistancesMeters
        .collectAsState()

    var newChannelName by remember {
        mutableStateOf("")
    }

    var newChannelPrivate by remember {
        mutableStateOf(false)
    }

    var newChannelPassword by remember {
        mutableStateOf("")
    }

    var pressing by remember {
        mutableStateOf(false)
    }

    val currentConnected =
        rememberUpdatedState(
            connected
        )

    val currentTalkStatus =
        rememberUpdatedState(
            talkStatus
        )

    val startSpeak =
        rememberUpdatedState(
            onStartSpeaking
        )

    val stopSpeak =
        rememberUpdatedState(
            onStopSpeaking
        )

    /*
     * ============================================================
     * 昵称
     * ============================================================
     */

    if (
        nicknameDialog
    ) {

        AlertDialog(

            onDismissRequest =
                onDismissNickname,

            title = {

                Text(

                    if (
                        nickname.isBlank()
                    ) {

                        "首次设置昵称"

                    } else {

                        "修改昵称"
                    }
                )
            },

            text = {

                Column {

                    if (
                        nickname.isBlank()
                    ) {

                        Text(

                            "请先设置昵称，设置完成后才能使用对讲功能。",

                            fontSize =
                                13.sp,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )

                        Spacer(
                            Modifier.height(
                                10.dp
                            )
                        )
                    }

                    OutlinedTextField(

                        value =
                            nicknameInput,

                        onValueChange =
                            onNicknameChanged,

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine =
                            true,

                        label = {

                            Text(
                                "我的昵称"
                            )
                        },

                        supportingText = {

                            Text(
                                "最多 20 个字符"
                            )
                        }
                    )
                }
            },

            confirmButton = {

                Button(

                    onClick =
                        onSaveNickname,

                    enabled =
                        nicknameInput
                            .trim()
                            .isNotBlank()

                ) {

                    Text(
                        "保存"
                    )
                }
            },

            dismissButton = {

                if (
                    nickname.isNotBlank()
                ) {

                    TextButton(

                        onClick =
                            onDismissNickname

                    ) {

                        Text(
                            "取消"
                        )
                    }
                }
            }
        )
    }

    /*
     * ============================================================
     * 创建频道
     * ============================================================
     */

    if (
        createChannelDialog
    ) {

        AlertDialog(

            onDismissRequest =
                onDismissCreate,

            title = {

                Text(
                    "创建频道"
                )
            },

            text = {

                Column {

                    OutlinedTextField(

                        value =
                            newChannelName,

                        onValueChange = {

                            newChannelName =
                                it.take(24)
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine =
                            true,

                        label = {

                            Text(
                                "频道名称"
                            )
                        }
                    )

                    Spacer(
                        Modifier.height(
                            10.dp
                        )
                    )

                    OutlinedButton(

                        onClick = {

                            newChannelPrivate =
                                !newChannelPrivate
                        },

                        modifier =
                            Modifier.fillMaxWidth()

                    ) {

                        Text(

                            if (
                                newChannelPrivate
                            ) {

                                "🔒 私密频道"

                            } else {

                                "🌐 公开频道"
                            }
                        )
                    }

                    if (
                        newChannelPrivate
                    ) {

                        Spacer(
                            Modifier.height(
                                8.dp
                            )
                        )

                        OutlinedTextField(

                            value =
                                newChannelPassword,

                            onValueChange = {

                                newChannelPassword =
                                    it.take(32)
                            },

                            modifier =
                                Modifier.fillMaxWidth(),

                            singleLine =
                                true,

                            label = {

                                Text(
                                    "频道密码"
                                )
                            }
                        )
                    }
                }
            },

            confirmButton = {

                Button(

                    onClick = {

                        onCreateChannel(

                            newChannelName
                                .trim(),

                            newChannelPrivate,

                            newChannelPassword
                                .trim()
                        )

                        newChannelName =
                            ""

                        newChannelPassword =
                            ""

                        newChannelPrivate =
                            false
                    },

                    enabled =
                        newChannelName
                            .trim()
                            .isNotBlank() &&
                                (
                                        !newChannelPrivate ||
                                                newChannelPassword
                                                    .trim()
                                                    .isNotBlank()
                                        )

                ) {

                    Text(
                        "创建"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    onClick =
                        onDismissCreate

                ) {

                    Text(
                        "取消"
                    )
                }
            }
        )
    }

    /*
     * ============================================================
     * 密码
     * ============================================================
     */

    if (
        joinPasswordDialog
    ) {

        AlertDialog(

            onDismissRequest =
                onDismissPassword,

            title = {

                Text(
                    "🔒 输入频道密码"
                )
            },

            text = {

                Column {

                    Text(

                        "频道：$joinPasswordChannel",

                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        Modifier.height(
                            10.dp
                        )
                    )

                    OutlinedTextField(

                        value =
                            joinPassword,

                        onValueChange =
                            onPasswordChanged,

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine =
                            true,

                        label = {

                            Text(
                                "密码"
                            )
                        }
                    )
                }
            },

            confirmButton = {

                Button(

                    onClick =
                        onConfirmPassword,

                    enabled =
                        joinPassword.isNotBlank()

                ) {

                    Text(
                        "加入"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    onClick =
                        onDismissPassword

                ) {

                    Text(
                        "取消"
                    )
                }
            }
        )
    }

    /*
     * ============================================================
     * 删除频道
     * ============================================================
     */

    if (
        deleteChannelDialog
    ) {

        AlertDialog(

            onDismissRequest =
                onDismissDelete,

            title = {

                Text(
                    "删除频道"
                )
            },

            text = {

                Text(
                    "确定删除「$currentChannel」吗？删除后无法恢复。"
                )
            },

            confirmButton = {

                Button(
                    onClick =
                        onConfirmDelete
                ) {

                    Text(
                        "确定删除"
                    )
                }
            },

            dismissButton = {

                TextButton(

                    onClick =
                        onDismissDelete

                ) {

                    Text(
                        "取消"
                    )
                }
            }
        )
    }

    /*
     * ============================================================
     * 主框架
     * ============================================================
     */

    Scaffold(

        bottomBar = {

            PttBottomBar(

                connected =
                    connected,

                talkStatus =
                    talkStatus,

                pressing =
                    pressing,

                currentConnected =
                    currentConnected,

                currentTalkStatus =
                    currentTalkStatus,

                startSpeak =
                    startSpeak,

                stopSpeak =
                    stopSpeak,

                pressingState = {

                    pressing =
                        it
                }
            )
        }

    ) { innerPadding ->

        when (
            page
        ) {

            /*
             * ========================================================
             * 首页
             * ========================================================
             */

            "home" -> {

                HomePage(

                    modifier =
                        Modifier.padding(
                            innerPadding
                        ),

                    connected =
                        connected,

                    nickname =
                        nickname,

                    myUserId =
                        myUserId,

                    onlineUsers =
                        onlineUsers,

                    memberDistancesMeters =
                        memberDistancesMeters,

                    currentChannel =
                        currentChannel,

                    currentOnlineCount =
                        currentOnlineCount,

                    currentPrivate =
                        currentPrivate,

                    networkType =
                        networkType,

                    onConnect =
                        onConnect,

                    onDisconnect =
                        onDisconnect,

                    onOpenUsers = {

                        onPageChanged(
                            "users"
                        )
                    },

                    onOpenChannels = {

                        onPageChanged(
                            "channels"
                        )
                    },

                    onOpenSettings = {

                        onPageChanged(
                            "settings"
                        )
                    }
                )
            }

            /*
             * ========================================================
             * 在线人员
             * ========================================================
             */

            "users" -> {

                UsersPage(

                    modifier =
                        Modifier.padding(
                            innerPadding
                        ),

                    connected =
                        connected,

                    currentChannel =
                        currentChannel,

                    currentOnlineCount =
                        currentOnlineCount,

                    onlineUsers =
                        onlineUsers,

                    myUserId =
                        myUserId,

                    memberDistancesMeters =
                        memberDistancesMeters,

                    onBack = {

                        onPageChanged(
                            "home"
                        )
                    }
                )
            }

            /*
             * ========================================================
             * 频道
             * ========================================================
             */

            "channels" -> {

                ChannelsPage(

                    modifier =
                        Modifier.padding(
                            innerPadding
                        ),

                    connected =
                        connected,

                    currentChannel =
                        currentChannel,

                    currentPrivate =
                        currentPrivate,

                    channels =
                        channels,

                    onBack = {

                        onPageChanged(
                            "home"
                        )
                    },

                    onRefresh =
                        onRefresh,

                    onSelectChannel =
                        onSelectChannel,

                    onOpenCreate =
                        onOpenCreate,

                    onOpenDelete =
                        onOpenDelete
                )
            }

            /*
             * ========================================================
             * 设置
             * ========================================================
             */

            "settings" -> {

                SettingsPage(

                    modifier =
                        Modifier.padding(
                            innerPadding
                        ),

                    nickname =
                        nickname,

                    myUserId =
                        myUserId,

                    networkType =
                        networkType,

                    onBack = {

                        onPageChanged(
                            "home"
                        )
                    },

                    onOpenNickname =
                        onOpenNickname
                )
            }
        }
    }
}

/*
 * ================================================================
 * 首页
 * ================================================================
 */

@Composable
private fun HomePage(
    modifier: Modifier,
    connected: Boolean,
    nickname: String,
    myUserId: String,
    onlineUsers: List<OnlineUserUiInfo>,
    memberDistancesMeters: Map<String, Double>,
    currentChannel: String,
    currentOnlineCount: Int,
    currentPrivate: Boolean,
    networkType: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenSettings: () -> Unit
) {

    Column(

        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    horizontal = 15.dp,
                    vertical = 9.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                9.dp
            )
    ) {

        /*
         * 顶部
         */

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(

                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {

                Text(

                    "兄弟对讲机",

                    fontSize =
                        27.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                Text(

                    "实时语音通信",

                    fontSize =
                        12.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            StatusPill(
                connected =
                    connected
            )
        }

        /*
         * ========================================================
         * 服务器
         * ========================================================
         */

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    19.dp
                ),

            colors =
                CardDefaults.cardColors(

                    containerColor =
                        if (
                            connected
                        ) {

                            MaterialTheme
                                .colorScheme
                                .primaryContainer

                        } else {

                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                        }
                )
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 15.dp,
                            vertical = 11.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(

                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Text(

                        "兄弟服务器",

                        fontSize =
                            11.sp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Text(

                        if (
                            connected
                        ) {

                            "已连接"

                        } else {

                            "未连接"
                        },

                        fontSize =
                            19.sp,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(

                        if (
                            networkType.isBlank()
                        ) {

                            "网络状态检测中"

                        } else {

                            networkType
                        },

                        fontSize =
                            11.sp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                if (
                    connected
                ) {

                    OutlinedButton(

                        onClick =
                            onDisconnect

                    ) {

                        Text(
                            "断开"
                        )
                    }

                } else {

                    Button(

                        onClick =
                            onConnect

                    ) {

                        Text(
                            "连接"
                        )
                    }
                }
            }
        }

        /*
         * ========================================================
         * 当前频道
         * ========================================================
         */

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    19.dp
                )
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 15.dp,
                            vertical = 10.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(

                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Text(

                        "当前频道",

                        fontSize =
                            11.sp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Text(

                        if (
                            currentPrivate
                        ) {

                            "🔒 $currentChannel"

                        } else {

                            "🌐 $currentChannel"
                        },

                        fontSize =
                            20.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }

                Column(

                    horizontalAlignment =
                        Alignment.End
                ) {

                    Text(

                        "在线",

                        fontSize =
                            11.sp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Text(

                        "$currentOnlineCount 人",

                        fontSize =
                            18.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }

        /*
         * ========================================================
         * 一级首页在线人员
         *
         * 排序：
         *
         * ① 自己
         * ② 最近成员
         * ③ 更远成员
         * ④ 没有 GPS 的成员
         *
         * 昵称缩小
         * 每行缩小
         * ========================================================
         */

        Card(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(
                        1f
                    ),

            shape =
                RoundedCornerShape(
                    19.dp
                )
        ) {

            Column(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 13.dp,
                            vertical = 10.dp
                        )
            ) {

                /*
                 * 标题
                 */

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Column(

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {

                        Text(

                            "在线人员",

                            fontSize =
                                18.sp,

                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(

                            if (
                                connected
                            ) {

                                "当前频道 · $currentOnlineCount 人"

                            } else {

                                "连接服务器后显示"
                            },

                            fontSize =
                                11.sp,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }

                    TextButton(

                        onClick =
                            onOpenUsers,

                        enabled =
                            connected

                    ) {

                        Text(
                            "全部"
                        )
                    }
                }

                Spacer(
                    Modifier.height(
                        5.dp
                    )
                )

                /*
                 * ==================================================
                 * 在线人员
                 * ==================================================
                 */

                if (
                    !connected
                ) {

                    EmptyCard(
                        "当前未连接服务器"
                    )

                } else if (
                    onlineUsers.isEmpty()
                ) {

                    EmptyCard(
                        "正在同步在线人员…"
                    )

                } else {

                    /*
                     * ==================================================
                     * 严格按照距离排序
                     * ==================================================
                     *
                     * 自己：
                     *   永远第一
                     *
                     * 其他成员：
                     *   距离越小越靠前
                     *
                     * 没有距离：
                     *   最后
                     */

                    val sortedOnlineUsers =
                        onlineUsers.sortedWith(

                            compareBy<OnlineUserUiInfo> {

                                /*
                                 * 自己第一
                                 */
                                if (
                                    it.userId ==
                                    myUserId &&
                                    myUserId.isNotBlank()
                                ) {

                                    0

                                } else {

                                    1
                                }

                            }.thenBy { user ->

                                /*
                                 * 按距离升序。
                                 *
                                 * 没有 GPS：
                                 * Double.MAX_VALUE
                                 *
                                 * 所以自动排最后。
                                 */
                                memberDistancesMeters[
                                    user.userId
                                ] ?: Double.MAX_VALUE

                            }.thenBy { user ->

                                /*
                                 * 如果距离相同，
                                 * 再按照昵称排序。
                                 */
                                user.nickname
                            }
                        )

                    Column(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min = 45.dp,
                                    max = 150.dp
                                )
                                .verticalScroll(
                                    rememberScrollState()
                                ),

                        verticalArrangement =
                            Arrangement.spacedBy(
                                4.dp
                            )
                    ) {

                        sortedOnlineUsers
                            .take(5)
                            .forEach { user ->

                                CompactOnlineUserRow(

                                    user =
                                        user,

                                    myUserId =
                                        myUserId,

                                    distanceMeters =
                                        memberDistancesMeters[
                                            user.userId
                                        ]
                                )
                            }
                    }

                    /*
                     * 超过 5 人
                     */

                    if (
                        sortedOnlineUsers.size > 5
                    ) {

                        TextButton(

                            onClick =
                                onOpenUsers,

                            modifier =
                                Modifier.fillMaxWidth()

                        ) {

                            Text(
                                "还有 ${sortedOnlineUsers.size - 5} 人，查看全部"
                            )
                        }
                    }
                }
            }
        }

        /*
         * ========================================================
         * 底部入口
         * ========================================================
         */

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(
                    9.dp
                )
        ) {

            OutlinedButton(

                onClick =
                    onOpenChannels,

                enabled =
                    connected,

                modifier =
                    Modifier.weight(
                        1f
                    )

            ) {

                Text(
                    "频道"
                )
            }

            Button(

                onClick =
                    onOpenSettings,

                modifier =
                    Modifier.weight(
                        1f
                    )

            ) {

                Text(
                    "⚙ 设置"
                )
            }
        }
    }
}

/*
 * ================================================================
 * 一级首页在线人员行
 *
 * 昵称缩小
 * 每行缩小
 * 距离直接显示
 * ================================================================
 */

@Composable
private fun CompactOnlineUserRow(
    user: OnlineUserUiInfo,
    myUserId: String,
    distanceMeters: Double?
) {

    val isMe =
        user.userId ==
                myUserId &&
                myUserId.isNotBlank()

    val distanceText =
        when {

            /*
             * 自己
             */
            isMe ->
                "——"

            /*
             * 有 GPS
             */
            distanceMeters != null ->
                formatDistanceForUi(
                    distanceMeters
                )

            /*
             * 暂时没有 GPS
             */
            else ->
                "定位中…"
        }

    Surface(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                11.dp
            ),

        color =
            MaterialTheme
                .colorScheme
                .surfaceVariant
    ) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 9.dp,
                        vertical = 5.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            /*
             * 小头像
             */

            Box(

                modifier =
                    Modifier
                        .size(
                            28.dp
                        )
                        .clip(
                            CircleShape
                        )
                        .background(
                            MaterialTheme
                                .colorScheme
                                .primaryContainer
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(

                    "👤",

                    fontSize =
                        14.sp
                )
            }

            Spacer(
                Modifier.width(
                    8.dp
                )
            )

            /*
             * 昵称
             */

            Column(

                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {

                Text(

                    user.nickname
                        .ifBlank {
                            "未命名用户"
                        },

                    fontSize =
                        13.sp,

                    fontWeight =
                        FontWeight.Medium,

                    maxLines =
                        1
                )

                Text(

                    if (
                        isMe
                    ) {

                        "我的设备"

                    } else {

                        "在线"
                    },

                    fontSize =
                        9.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }

            /*
             * 距离
             */

            Column(

                horizontalAlignment =
                    Alignment.End
            ) {

                Text(

                    distanceText,

                    fontSize =
                        13.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                if (
                    !isMe &&
                    distanceMeters != null
                ) {

                    Text(

                        "直线距离",

                        fontSize =
                            8.sp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }

            Spacer(
                Modifier.width(
                    7.dp
                )
            )

            Text(

                "🟢",

                fontSize =
                    10.sp
            )
        }
    }
}

/*
 * ================================================================
 * 在线人员页面
 * ================================================================
 */

@Composable
private fun UsersPage(
    modifier: Modifier,
    connected: Boolean,
    currentChannel: String,
    currentOnlineCount: Int,
    onlineUsers: List<OnlineUserUiInfo>,
    myUserId: String,
    memberDistancesMeters: Map<String, Double>,
    onBack: () -> Unit
) {

    Column(

        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            )
    ) {

        PageHeader(

            title =
                "在线人员",

            subtitle =
                "频道：$currentChannel",

            onBack =
                onBack
        )

        InfoCard(

            title =
                "当前在线人数",

            value =

                if (
                    connected
                ) {

                    "$currentOnlineCount 人"

                } else {

                    "未连接"
                },

            extra =
                "人数来自服务器当前频道成员列表"
        )

        if (
            !connected
        ) {

            EmptyCard(
                "当前未连接服务器"
            )

        } else if (
            onlineUsers.isEmpty()
        ) {

            EmptyCard(
                "正在同步在线人员…"
            )

        } else {

            onlineUsers.forEach { user ->

                val isMe =
                    user.userId ==
                            myUserId &&
                            myUserId.isNotBlank()

                val distanceMeters =
                    memberDistancesMeters[
                        user.userId
                    ]

                val distanceText =
                    when {

                        isMe ->
                            "——"

                        distanceMeters != null ->
                            formatDistanceForUi(
                                distanceMeters
                            )

                        else ->
                            "定位中…"
                    }

                Card(

                    modifier =
                        Modifier.fillMaxWidth(),

                    shape =
                        RoundedCornerShape(
                            18.dp
                        )
                ) {

                    Row(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 12.dp,
                                    vertical = 9.dp
                                ),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Box(

                            modifier =
                                Modifier
                                    .size(
                                        38.dp
                                    )
                                    .clip(
                                        CircleShape
                                    )
                                    .background(
                                        MaterialTheme
                                            .colorScheme
                                            .primaryContainer
                                    ),

                            contentAlignment =
                                Alignment.Center
                        ) {

                            Text(
                                "👤",
                                fontSize =
                                    18.sp
                            )
                        }

                        Spacer(
                            Modifier.width(
                                10.dp
                            )
                        )

                        Column(

                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {

                            Text(

                                user.nickname
                                    .ifBlank {
                                        "未命名用户"
                                    },

                                fontSize =
                                    15.sp,

                                fontWeight =
                                    FontWeight.Bold,

                                maxLines =
                                    1
                            )

                            Text(

                                if (
                                    isMe
                                ) {

                                    "我的设备"

                                } else {

                                    "在线"
                                },

                                fontSize =
                                    10.sp,

                                color =
                                    Color.Gray
                            )
                        }

                        Column(

                            horizontalAlignment =
                                Alignment.End
                        ) {

                            Text(

                                distanceText,

                                fontSize =
                                    14.sp,

                                fontWeight =
                                    FontWeight.Bold
                            )

                            Text(

                                if (
                                    isMe
                                ) {

                                    ""

                                } else if (
                                    distanceMeters != null
                                ) {

                                    "直线距离"

                                } else {

                                    "GPS"
                                },

                                fontSize =
                                    9.sp,

                                color =
                                    Color.Gray
                            )
                        }

                        Spacer(
                            Modifier.width(
                                8.dp
                            )
                        )

                        Text(
                            "🟢",
                            fontSize =
                                12.sp
                        )
                    }
                }
            }
        }
    }
}

/*
 * ================================================================
 * 频道页面
 * ================================================================
 */

@Composable
private fun ChannelsPage(
    modifier: Modifier,
    connected: Boolean,
    currentChannel: String,
    currentPrivate: Boolean,
    channels: List<ChannelUiInfo>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectChannel: (ChannelUiInfo) -> Unit,
    onOpenCreate: () -> Unit,
    onOpenDelete: () -> Unit
) {

    Column(

        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {

        PageHeader(

            title =
                "频道",

            subtitle =
                "切换和管理频道",

            onBack =
                onBack
        )

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {

            Button(

                onClick =
                    onOpenCreate,

                enabled =
                    connected,

                modifier =
                    Modifier.weight(
                        1f
                    )

            ) {

                Text(
                    "＋ 创建"
                )
            }

            OutlinedButton(

                onClick =
                    onRefresh,

                enabled =
                    connected,

                modifier =
                    Modifier.weight(
                        1f
                    )

            ) {

                Text(
                    "刷新"
                )
            }
        }

        OutlinedButton(

            onClick =
                onOpenDelete,

            enabled =
                connected &&
                        currentChannel !=
                        "public",

            modifier =
                Modifier.fillMaxWidth()

        ) {

            Text(
                "删除当前频道"
            )
        }

        if (
            channels.isEmpty()
        ) {

            EmptyCard(
                "暂无频道"
            )
        }

        channels.forEach { channel ->

            Card(

                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(
                        16.dp
                    )
            ) {

                Row(

                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                15.dp
                            ),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Column(

                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {

                        Text(

                            if (
                                channel.isPrivate
                            ) {

                                "🔒 ${channel.name}"

                            } else {

                                "🌐 ${channel.name}"
                            },

                            fontSize =
                                18.sp,

                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(

                            "在线 ${channel.onlineCount} 人",

                            fontSize =
                                12.sp,

                            color =
                                Color.Gray
                        )
                    }

                    if (
                        channel.name ==
                        currentChannel
                    ) {

                        Text(

                            "当前",

                            fontWeight =
                                FontWeight.Bold
                        )

                    } else {

                        OutlinedButton(

                            onClick = {

                                onSelectChannel(
                                    channel
                                )
                            },

                            enabled =
                                connected

                        ) {

                            Text(

                                if (
                                    channel.isPrivate ||
                                    channel.requirePassword
                                ) {

                                    "加入"

                                } else {

                                    "切换"
                                }
                            )
                        }
                    }
                }
            }
        }

        InfoCard(

            title =
                "当前频道",

            value =

                if (
                    currentPrivate
                ) {

                    "🔒 $currentChannel"

                } else {

                    "🌐 $currentChannel"
                }
        )
    }
}

/*
 * ================================================================
 * 设置
 * ================================================================
 */

@Composable
private fun SettingsPage(
    modifier: Modifier,
    nickname: String,
    myUserId: String,
    networkType: String,
    onBack: () -> Unit,
    onOpenNickname: () -> Unit
) {

    Column(

        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 10.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {

        PageHeader(

            title =
                "设置",

            subtitle =
                "个人与应用信息",

            onBack =
                onBack
        )

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    19.dp
                )
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            15.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(

                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(
                                CircleShape
                            )
                            .background(
                                MaterialTheme
                                    .colorScheme
                                    .primaryContainer
                            ),

                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        "👤",
                        fontSize =
                            21.sp
                    )
                }

                Spacer(
                    Modifier.width(
                        11.dp
                    )
                )

                Column(

                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Text(

                        "昵称",

                        fontSize =
                            12.sp,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )

                    Text(

                        nickname.ifBlank {
                            "未设置"
                        },

                        fontSize =
                            17.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }

                OutlinedButton(

                    onClick =
                        onOpenNickname

                ) {

                    Text(
                        "修改"
                    )
                }
            }
        }

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    19.dp
                )
        ) {

            Column(

                modifier =
                    Modifier.padding(
                        15.dp
                    )
            ) {

                Text(

                    "网络状态",

                    fontSize =
                        12.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Spacer(
                    Modifier.height(
                        4.dp
                    )
                )

                Text(

                    networkType,

                    fontSize =
                        15.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    Modifier.height(
                        4.dp
                    )
                )

                Text(

                    "网络数据会持续保留，直到收到新的检测结果。",

                    fontSize =
                        11.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    19.dp
                )
        ) {

            Column(

                Modifier.padding(
                    15.dp
                )
            ) {

                Text(

                    "用户标识",

                    fontSize =
                        12.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Text(

                    myUserId.ifBlank {
                        "连接后由服务器分配"
                    },

                    fontSize =
                        15.sp,

                    fontWeight =
                        FontWeight.Bold
                )
            }
        }

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    19.dp
                )
        ) {

            Column(

                Modifier.padding(
                    15.dp
                )
            ) {

                Text(

                    "关于兄弟对讲机",

                    fontSize =
                        17.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                Text(

                    "V24.9.1",

                    fontSize =
                        12.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )

                Spacer(
                    Modifier.height(
                        4.dp
                    )
                )

                Text(

                    "服务器地址已隐藏",

                    fontSize =
                        11.sp,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant
                )
            }
        }
    }
}

/*
 * ================================================================
 * PTT
 * ================================================================
 */

@Composable
private fun PttBottomBar(
    connected: Boolean,
    talkStatus: String,
    pressing: Boolean,
    currentConnected: State<Boolean>,
    currentTalkStatus: State<String>,
    startSpeak: State<() -> Unit>,
    stopSpeak: State<() -> Unit>,
    pressingState: (Boolean) -> Unit
) {

    Surface(

        shadowElevation =
            8.dp
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 9.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(

                when {

                    !connected ->
                        "连接服务器后即可讲话"

                    talkStatus ==
                            WalkieService.TALK_STATUS_ALLOWED ->
                        "正在讲话 · 松开结束"

                    talkStatus ==
                            WalkieService.TALK_STATUS_REQUESTING ->
                        "正在抢麦…请保持按住"

                    talkStatus ==
                            WalkieService.TALK_STATUS_BUSY ->
                        "频道正在通话"

                    pressing ->
                        "讲话请求已发送"

                    else ->
                        "按住说话"
                },

                fontSize =
                    13.sp,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(
                    6.dp
                )
            )

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            76.dp
                        )
                        .clip(
                            RoundedCornerShape(
                                22.dp
                            )
                        )
                        .background(

                            when {

                                !connected ->
                                    Color(
                                        0xFF9E9E9E
                                    )

                                talkStatus ==
                                        WalkieService.TALK_STATUS_ALLOWED ->
                                    Color(
                                        0xFFD32F2F
                                    )

                                talkStatus ==
                                        WalkieService.TALK_STATUS_REQUESTING ->
                                    Color(
                                        0xFFF57C00
                                    )

                                talkStatus ==
                                        WalkieService.TALK_STATUS_BUSY ->
                                    Color(
                                        0xFF616161
                                    )

                                pressing ->
                                    Color(
                                        0xFFC62828
                                    )

                                else ->
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                            }
                        )
                        .pointerInput(Unit) {

                            awaitEachGesture {

                                val down =
                                    awaitFirstDown(
                                        requireUnconsumed =
                                            false
                                    )

                                down.consume()

                                val canStart =
                                    currentConnected.value &&
                                            currentTalkStatus.value !=
                                            WalkieService.TALK_STATUS_BUSY &&
                                            currentTalkStatus.value !=
                                            WalkieService.TALK_STATUS_ALLOWED

                                if (
                                    !canStart
                                ) {

                                    pressingState(
                                        false
                                    )

                                    return@awaitEachGesture
                                }

                                pressingState(
                                    true
                                )

                                startSpeak
                                    .value
                                    .invoke()

                                try {

                                    while (
                                        true
                                    ) {

                                        val event =
                                            awaitPointerEvent()

                                        val change =
                                            event
                                                .changes
                                                .firstOrNull {
                                                    it.id ==
                                                            down.id
                                                }

                                        if (
                                            change ==
                                            null ||
                                            !change.pressed
                                        ) {

                                            change?.consume()

                                            break
                                        }

                                        change.consume()
                                    }

                                } finally {

                                    pressingState(
                                        false
                                    )

                                    stopSpeak
                                        .value
                                        .invoke()
                                }
                            }
                        },

                contentAlignment =
                    Alignment.Center

            ) {

                Row(

                    verticalAlignment =
                        Alignment.CenterVertically,

                    horizontalArrangement =
                        Arrangement.Center
                ) {

                    Text(

                        "🎙",

                        fontSize =
                            30.sp
                    )

                    Spacer(
                        Modifier.width(
                            12.dp
                        )
                    )

                    Text(

                        when {

                            !connected ->
                                "连接后使用"

                            talkStatus ==
                                    WalkieService.TALK_STATUS_ALLOWED ->
                                "松开结束讲话"

                            talkStatus ==
                                    WalkieService.TALK_STATUS_REQUESTING ->
                                "抢麦中 · 不要松开"

                            talkStatus ==
                                    WalkieService.TALK_STATUS_BUSY ->
                                "忙线"

                            else ->
                                "按住说话"
                        },

                        fontSize =
                            22.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color.White
                    )
                }
            }
        }
    }
}

/*
 * ================================================================
 * FeatureCard
 * ================================================================
 */

@Composable
private fun FeatureCard(
    icon: String,
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true
) {

    Card(

        modifier =
            modifier,

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    MaterialTheme
                        .colorScheme
                        .surfaceVariant
            )
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        14.dp
                    )
        ) {

            Text(

                icon,

                fontSize =
                    24.sp
            )

            Spacer(
                Modifier.height(
                    4.dp
                )
            )

            Text(

                title,

                fontSize =
                    16.sp,

                fontWeight =
                    FontWeight.Bold
            )

            Text(

                value,

                fontSize =
                    12.sp,

                color =
                    Color.Gray
            )

            Spacer(
                Modifier.height(
                    8.dp
                )
            )

            Button(

                onClick =
                    onClick,

                enabled =
                    enabled,

                modifier =
                    Modifier.fillMaxWidth()

            ) {

                Text(
                    "打开"
                )
            }
        }
    }
}

/*
 * ================================================================
 * 页面标题
 * ================================================================
 */

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {

    Row(

        modifier =
            Modifier.fillMaxWidth(),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        TextButton(

            onClick =
                onBack

        ) {

            Text(
                "‹ 返回"
            )
        }

        Column(

            modifier =
                Modifier.weight(
                    1f
                )
        ) {

            Text(

                title,

                fontSize =
                    24.sp,

                fontWeight =
                    FontWeight.Bold
            )

            Text(

                subtitle,

                fontSize =
                    12.sp,

                color =
                    Color.Gray
            )
        }
    }
}

/*
 * ================================================================
 * 状态
 * ================================================================
 */

@Composable
private fun StatusPill(
    connected: Boolean
) {

    Card(

        shape =
            RoundedCornerShape(
                50.dp
            ),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    if (
                        connected
                    ) {

                        MaterialTheme
                            .colorScheme
                            .primaryContainer

                    } else {

                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    }
            )
    ) {

        Text(

            if (
                connected
            ) {

                "🟢 在线"

            } else {

                "🔴 离线"
            },

            modifier =
                Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 7.dp
                ),

            fontWeight =
                FontWeight.Bold
        )
    }
}

/*
 * ================================================================
 * 信息卡
 * ================================================================
 */

@Composable
private fun InfoCard(
    title: String,
    value: String,
    extra: String = ""
) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                18.dp
            )
    ) {

        Column(

            Modifier.padding(
                16.dp
            )
        ) {

            Text(

                title,

                fontSize =
                    12.sp,

                color =
                    Color.Gray
            )

            Text(

                value,

                fontSize =
                    18.sp,

                fontWeight =
                    FontWeight.Bold
            )

            if (
                extra.isNotBlank()
            ) {

                Text(

                    extra,

                    fontSize =
                        12.sp,

                    color =
                        Color.Gray
                )
            }
        }
    }
}

/*
 * ================================================================
 * 空卡片
 * ================================================================
 */

@Composable
private fun EmptyCard(
    text: String
) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                15.dp
            )
    ) {

        Box(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        18.dp
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(

                text,

                fontSize =
                    12.sp,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

/*
 * ================================================================
 * 距离格式化
 * ================================================================
 */

private fun formatDistanceForUi(
    distanceMeters: Double
): String {

    if (
        distanceMeters < 1000.0
    ) {

        return String.format(
            java.util.Locale.US,
            "%.0f m",
            distanceMeters
        )
    }

    return String.format(
        java.util.Locale.US,
        "%.2f km",
        distanceMeters / 1000.0
    )
}