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

private const val WALKIE_VERSION = "V20"

private const val DEFAULT_SERVER_IP = "38.146.29.169"

private const val UI_PREFS =
    "walkie_session_v20"

private const val UI_PREF_NICKNAME =
    "nickname"

data class ChannelUiInfo(
    val name: String,
    val onlineCount: Int = 0,
    val isPrivate: Boolean = false,
    val requirePassword: Boolean = false
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
     * 网络状态直接显示在首页。
     */
    private var networkType by mutableStateOf(
        "检测中"
    )

    /*
     * ============================================================
     * 系统返回
     *
     * 不使用 Compose BackHandler。
     * 页面返回交给 Activity 统一处理。
     * ============================================================
     */

    private var currentPage by mutableStateOf(
        "home"
    )

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

                /*
                 * 首页才真正交给系统返回。
                 */
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
            }
        }

    private val requestNotification =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
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
                     * ------------------------------------------------
                     * 连接状态
                     * ------------------------------------------------
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

                            talkStatus =
                                WalkieService.TALK_STATUS_NONE

                            currentOnlineCount =
                                0
                        }

                        updateNetworkType()

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "连接状态=$connected"
                        )
                    }

                    /*
                     * ------------------------------------------------
                     * 抢麦
                     * ------------------------------------------------
                     */

                    WalkieService.ACTION_TALK_STATUS -> {

                        talkStatus =
                            intent.getStringExtra(
                                WalkieService.EXTRA_TALK_STATUS
                            )
                                ?: WalkieService.TALK_STATUS_NONE

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "TALK STATUS=$talkStatus"
                        )
                    }

                    /*
                     * ------------------------------------------------
                     * 频道列表
                     *
                     * 收到这个广播说明：
                     *
                     * Activity -> Service
                     * Service -> UDP服务器
                     * UDP服务器 -> Service
                     * Service -> Activity
                     *
                     * 整个链路已经正常。
                     *
                     * 所以即使 Activity 之前显示“未连接”，
                     * 收到这里也直接恢复为在线。
                     * ------------------------------------------------
                     */

                    WalkieService.ACTION_CHANNEL_LIST -> {

                        val infos =
                            intent.getStringArrayListExtra(
                                WalkieService.EXTRA_CHANNEL_INFO
                            ).orEmpty()

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
                     * ------------------------------------------------
                     * 当前频道
                     * ------------------------------------------------
                     */

                    WalkieService.ACTION_CHANNEL_STATUS -> {

                        currentChannel =
                            intent.getStringExtra(
                                WalkieService.EXTRA_CURRENT_CHANNEL
                            )
                                ?: currentChannel

                        currentOnlineCount =
                            intent.getIntExtra(
                                WalkieService.EXTRA_CHANNEL_ONLINE_COUNT,
                                currentOnlineCount
                            )
                                .coerceAtLeast(0)

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

                        connectionState =
                            true

                        updateNetworkType()

                        println(
                            "WALKIE $WALKIE_VERSION: " +
                                    "频道=$currentChannel " +
                                    "人数=$currentOnlineCount"
                        )
                    }

                    /*
                     * ------------------------------------------------
                     * 删除频道
                     * ------------------------------------------------
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

        /*
         * 注册系统返回。
         */
        onBackPressedDispatcher.addCallback(
            this,
            systemBackCallback
        )

        /*
         * 读取本地昵称。
         */
        nickname =
            getSharedPreferences(
                UI_PREFS,
                MODE_PRIVATE
            )
                .getString(
                    UI_PREF_NICKNAME,
                    ""
                )
                ?: ""

        updateNetworkType()

        requestPermissionsIfNeeded()

        /*
         * 注册广播。
         */
        val filter =
            IntentFilter().apply {

                addAction(
                    WalkieService.ACTION_CONNECTION_STATUS
                )

                addAction(
                    WalkieService.ACTION_TALK_STATUS
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

        /*
         * ========================================================
         * Compose
         * ========================================================
         */

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

                        currentChannel =
                            currentChannel,

                        currentOnlineCount =
                            currentOnlineCount,

                        currentPrivate =
                            currentPrivate,

                        channels =
                            channelList,

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

                            nicknameDialog =
                                false
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
     *
     * 关键修复：
     *
     * 不再要求 connectionState=true 才发送频道请求。
     *
     * 因为 Activity 刚创建的时候：
     *
     * connectionState=false
     *
     * 但 Service 可能实际上还在线。
     *
     * 所以必须主动问 Service。
     * ============================================================
     */

    override fun onResume() {

        super.onResume()

        updateNetworkType()

        /*
         * 无论 Activity 自己认为在线还是离线，
         * 都主动询问 Service。
         */
        requestChannelList()
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
            }
        }
    }

    /*
     * ============================================================
     * 网络
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

            networkType =
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

        } catch (_: Exception) {

            networkType =
                "未知"
        }
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
                "WALKIE $WALKIE_VERSION: " +
                        "请求连接兄弟服务器"
            )

        } catch (e: Exception) {

            println(
                "WALKIE $WALKIE_VERSION: " +
                        "连接失败=${e.message}"
            )
        }
    }

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
            nicknameInput.trim()

        if (
            value.isBlank()
        ) {

            return
        }

        nickname =
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

        println(
            "WALKIE $WALKIE_VERSION: " +
                    "昵称=$value"
        )
    }

    /*
     * ============================================================
     * 频道列表
     *
     * 注意：
     * 这里故意不能写：
     *
     * if (!connectionState) return
     *
     * 因为本函数就是用来恢复连接显示的。
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
     * 解析频道
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
 * V20 主界面
 * ================================================================
 */

@Composable
private fun WalkieV20Screen(
    connected: Boolean,
    talkStatus: String,
    nickname: String,
    currentChannel: String,
    currentOnlineCount: Int,
    currentPrivate: Boolean,
    channels: List<ChannelUiInfo>,
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
     * Compose 内部状态
     *
     * 全部 remember。
     * ============================================================
     */

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

    /*
     * PTT 使用 rememberUpdatedState。
     *
     * pointerInput 的 key 仍然固定为 Unit。
     */
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
     * 昵称弹窗
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
                    "设置昵称"
                )
            },

            text = {

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

                TextButton(
                    onClick =
                        onDismissNickname
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
     * 加入密码频道
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
     * 页面
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

                    onOpenNickname =
                        onOpenNickname,

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

                    onBack = {

                        onPageChanged(
                            "home"
                        )
                    }
                )
            }

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

            "settings" -> {

                SettingsPage(

                    modifier =
                        Modifier.padding(
                            innerPadding
                        ),

                    nickname =
                        nickname,

                    onBack = {

                        onPageChanged(
                            "home"
                        )
                    },

                    onOpenNickname =
                        onOpenNickname
                )
            }

            else -> {

                onPageChanged(
                    "home"
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
    currentChannel: String,
    currentOnlineCount: Int,
    currentPrivate: Boolean,
    networkType: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNickname: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenSettings: () -> Unit
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
                    vertical = 14.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                11.dp
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
                    Modifier.weight(1f)
            ) {

                Text(

                    "兄弟对讲机",

                    fontSize =
                        28.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                Text(

                    "V20 · 实时语音通信",

                    fontSize =
                        13.sp,

                    color =
                        Color.Gray
                )
            }

            StatusPill(
                connected =
                    connected
            )
        }

        /*
         * ========================================================
         * 我的昵称
         * ========================================================
         */

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    20.dp
                )
        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Box(

                    modifier =
                        Modifier
                            .size(52.dp)
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
                            24.sp
                    )
                }

                Spacer(
                    Modifier.width(
                        12.dp
                    )
                )

                Column(

                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(

                        "我的昵称",

                        fontSize =
                            12.sp,

                        color =
                            Color.Gray
                    )

                    Text(

                        nickname.ifBlank {
                            "未设置昵称"
                        },

                        fontSize =
                            19.sp,

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
                    20.dp
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
                        .padding(16.dp),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(

                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(

                        "兄弟服务器",

                        fontSize =
                            12.sp,

                        color =
                            Color.Gray
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
                            20.sp,

                        fontWeight =
                            FontWeight.Bold
                    )

                    Text(

                        networkType,

                        fontSize =
                            12.sp,

                        color =
                            Color.Gray
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
                    20.dp
                )
        ) {

            Column(

                Modifier.padding(
                    16.dp
                )
            ) {

                Row(

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Column(

                        modifier =
                            Modifier.weight(1f)
                    ) {

                        Text(

                            "当前频道",

                            fontSize =
                                12.sp,

                            color =
                                Color.Gray
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
                                22.sp,

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
                                12.sp,

                            color =
                                Color.Gray
                        )

                        Text(

                            "$currentOnlineCount",

                            fontSize =
                                29.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }
                }

                Spacer(
                    Modifier.height(
                        10.dp
                    )
                )

                Button(

                    onClick =
                        onOpenChannels,

                    enabled =
                        connected,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "频道管理"
                    )
                }
            }
        }

        /*
         * ========================================================
         * 第一排：在线人员 + 网络状态
         *
         * 网络状态直接显示在首页，
         * 不再做网络二级页面。
         * ========================================================
         */

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(
                    10.dp
                )
        ) {

            FeatureCard(

                icon =
                    "👥",

                title =
                    "在线人员",

                value =
                    "$currentOnlineCount 人",

                onClick =
                    onOpenUsers,

                modifier =
                    Modifier.weight(1f)
            )

            FeatureCard(

                icon =
                    "📶",

                title =
                    "网络状态",

                value =
                    networkType,

                onClick = {},

                modifier =
                    Modifier.weight(1f),

                enabled =
                    false
            )
        }

        /*
         * 第二排
         */

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(
                    10.dp
                )
        ) {

            FeatureCard(

                icon =
                    "⚙️",

                title =
                    "设置",

                value =
                    "昵称与应用信息",

                onClick =
                    onOpenSettings,

                modifier =
                    Modifier.weight(1f)
            )

            FeatureCard(

                icon =
                    "🎙",

                title =
                    "实时通话",

                value =

                    if (
                        connected
                    ) {

                        "按住底部说话"

                    } else {

                        "连接后使用"
                    },

                onClick = {},

                modifier =
                    Modifier.weight(1f),

                enabled =
                    false
            )
        }

        /*
         * 在线提示
         */

        if (
            connected
        ) {

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
                            .padding(15.dp),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        "●",
                        fontSize =
                            15.sp
                    )

                    Spacer(
                        Modifier.width(
                            8.dp
                        )
                    )

                    Column {

                        Text(

                            "服务器连接正常",

                            fontWeight =
                                FontWeight.Bold
                        )

                        Text(

                            "当前频道：$currentChannel",

                            fontSize =
                                12.sp,

                            color =
                                Color.Gray
                        )
                    }
                }
            }
        }

        Spacer(
            Modifier.height(
                6.dp
            )
        )
    }
}

/*
 * ================================================================
 * 在线人员
 * ================================================================
 */

@Composable
private fun UsersPage(
    modifier: Modifier,
    connected: Boolean,
    currentChannel: String,
    currentOnlineCount: Int,
    onBack: () -> Unit
) {

    Column(

        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(16.dp),

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
                "当前 V19 服务器提供的是频道在线人数"
        )

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

                    "在线成员",

                    fontSize =
                        17.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    Modifier.height(
                        8.dp
                    )
                )

                Text(

                    "当前服务端协议还没有返回逐个用户昵称的接口，" +
                            "所以这里暂时显示频道总人数。",

                    fontSize =
                        13.sp,

                    color =
                        Color.Gray
                )
            }
        }
    }
}

/*
 * ================================================================
 * 频道
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
                .padding(16.dp),

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
                    Modifier.weight(1f)

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
                    Modifier.weight(1f)

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

        channels.forEach {

                channel ->

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
                            .padding(15.dp),

                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Column(

                        modifier =
                            Modifier.weight(1f)
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
                .padding(16.dp),

        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            )
    ) {

        PageHeader(

            title =
                "设置",

            subtitle =
                "账号与应用信息",

            onBack =
                onBack
        )

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

                    "昵称",

                    fontSize =
                        12.sp,

                    color =
                        Color.Gray
                )

                Text(

                    nickname.ifBlank {
                        "未设置"
                    },

                    fontSize =
                        20.sp,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    Modifier.height(
                        10.dp
                    )
                )

                OutlinedButton(

                    onClick =
                        onOpenNickname,

                    modifier =
                        Modifier.fillMaxWidth()

                ) {

                    Text(
                        "修改昵称"
                    )
                }
            }
        }

        InfoCard(

            title =
                "服务器",

            value =
                "兄弟服务器",

            extra =
                "服务器地址已隐藏"
        )

        InfoCard(

            title =
                "版本",

            value =
                "V20"
        )

        Text(

            "当前界面不会显示服务器 IP。",

            fontSize =
                12.sp,

            color =
                Color.Gray
        )
    }
}

/*
 * ================================================================
 * PTT 底部按钮
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
                        vertical = 10.dp
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
                    14.sp,

                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(
                    7.dp
                )
            )

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(
                            RoundedCornerShape(
                                22.dp
                            )
                        )
                        .background(

                            when {

                                !connected ->
                                    Color(0xFF9E9E9E)

                                talkStatus ==
                                        WalkieService.TALK_STATUS_ALLOWED ->
                                    Color(0xFFD32F2F)

                                talkStatus ==
                                        WalkieService.TALK_STATUS_REQUESTING ->
                                    Color(0xFFF57C00)

                                talkStatus ==
                                        WalkieService.TALK_STATUS_BUSY ->
                                    Color(0xFF616161)

                                pressing ->
                                    Color(0xFFC62828)

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

                                /*
                                 * 一旦手指按下，
                                 * 开始请求抢麦。
                                 *
                                 * pointerInput 固定为 Unit，
                                 * 因此状态改变不会取消当前手势。
                                 */
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
 * 功能卡片
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
                    .padding(14.dp)
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
                Modifier.weight(1f)
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
 * 在线状态
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
 * 空页面
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
                18.dp
            )
    ) {

        Box(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        30.dp
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(

                text,

                color =
                    Color.Gray
            )
        }
    }
}