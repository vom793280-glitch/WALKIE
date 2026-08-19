package com.example.walkie

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.walkie.ui.theme.WalkieTheme

private const val WALKIE_VERSION = "V11"

class MainActivity : ComponentActivity() {

    private var connectionState by mutableStateOf(false)

    private var talkStatus by mutableStateOf(
        WalkieService.TALK_STATUS_NONE
    )

    private var currentChannel by mutableStateOf(
        "public"
    )

    private var currentChannelOnlineCount by mutableStateOf(0)

    private var currentChannelPrivate by mutableStateOf(false)

    private var currentChannelRequirePassword by mutableStateOf(false)

    private var channelList by mutableStateOf(
        listOf(
            ChannelUiInfo(
                name = "public"
            )
        )
    )

    private var channelMessage by mutableStateOf("")

    private var pttSpeaking by mutableStateOf(false)

    private var createChannelDialog by mutableStateOf(false)

    private var joinPasswordDialog by mutableStateOf(false)

    private var joinPasswordChannel by mutableStateOf("")

    private var joinPassword by mutableStateOf("")

    private var deleteChannelDialog by mutableStateOf(false)

    /*
     * ============================================================
     * 麦克风权限
     * ============================================================
     */

    private val requestMicrophone =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            println(
                "WALKIE $WALKIE_VERSION: 麦克风权限=$granted"
            )

            if (granted) {
                requestNotificationPermissionIfNeeded()
            }
        }

    /*
     * ============================================================
     * 通知权限
     * ============================================================
     */

    private val requestNotification =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            println(
                "WALKIE $WALKIE_VERSION: 通知权限=$granted"
            )
        }

    /*
     * ============================================================
     * 广播
     * ============================================================
     */

    private val connectionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent == null) {
                    return
                }

                when (intent.action) {

                    WalkieService.ACTION_CONNECTION_STATUS -> {

                        val connected =
                            intent.getBooleanExtra(
                                WalkieService.EXTRA_CONNECTED,
                                false
                            )

                        connectionState =
                            connected

                        println(
                            "WALKIE $WALKIE_VERSION: 连接状态=$connected"
                        )

                        if (!connected) {

                            talkStatus =
                                WalkieService.TALK_STATUS_NONE

                            pttSpeaking = false

                            currentChannel = "public"

                            currentChannelOnlineCount = 0

                            currentChannelPrivate = false

                            currentChannelRequirePassword = false

                            channelList =
                                listOf(
                                    ChannelUiInfo(
                                        name = "public"
                                    )
                                )
                        }
                    }

                    WalkieService.ACTION_TALK_STATUS -> {

                        val status =
                            intent.getStringExtra(
                                WalkieService.EXTRA_TALK_STATUS
                            )
                                ?: WalkieService.TALK_STATUS_NONE

                        talkStatus =
                            status

                        if (
                            status ==
                            WalkieService.TALK_STATUS_BUSY ||
                            status ==
                            WalkieService.TALK_STATUS_RELEASED
                        ) {

                            pttSpeaking = false
                        }

                        println(
                            "WALKIE $WALKIE_VERSION: TALK STATUS=$status"
                        )
                    }

                    WalkieService.ACTION_CHANNEL_LIST -> {

                        val infoStrings =
                            intent.getStringArrayListExtra(
                                WalkieService.EXTRA_CHANNEL_INFO
                            )

                        if (!infoStrings.isNullOrEmpty()) {

                            val parsed =
                                infoStrings.mapNotNull {
                                    parseChannelInfo(it)
                                }

                            if (parsed.isNotEmpty()) {

                                channelList =
                                    parsed
                                        .distinctBy {
                                            it.name
                                        }
                                        .sortedBy {
                                            it.name
                                        }
                            }

                        } else {

                            val names =
                                intent.getStringArrayListExtra(
                                    WalkieService.EXTRA_CHANNEL_LIST
                                )
                                    ?: arrayListOf()

                            if (names.isNotEmpty()) {

                                channelList =
                                    names
                                        .distinct()
                                        .map {
                                            ChannelUiInfo(
                                                name = it
                                            )
                                        }
                                        .sortedBy {
                                            it.name
                                        }
                            }
                        }

                        println(
                            "WALKIE $WALKIE_VERSION: 频道列表=$channelList"
                        )
                    }

                    WalkieService.ACTION_CHANNEL_STATUS -> {

                        val channel =
                            intent.getStringExtra(
                                WalkieService.EXTRA_CURRENT_CHANNEL
                            )

                        if (!channel.isNullOrBlank()) {
                            currentChannel =
                                channel
                        }

                        currentChannelOnlineCount =
                            intent.getIntExtra(
                                WalkieService.EXTRA_CHANNEL_ONLINE_COUNT,
                                currentChannelOnlineCount
                            )

                        currentChannelPrivate =
                            intent.getBooleanExtra(
                                WalkieService.EXTRA_CHANNEL_PRIVATE,
                                currentChannelPrivate
                            )

                        currentChannelRequirePassword =
                            intent.getBooleanExtra(
                                WalkieService.EXTRA_CHANNEL_REQUIRE_PASSWORD,
                                currentChannelRequirePassword
                            )

                        val message =
                            intent.getStringExtra(
                                WalkieService.EXTRA_CHANNEL_MESSAGE
                            )

                        if (!message.isNullOrBlank()) {
                            channelMessage = message
                        }

                        updateCurrentChannelInList()

                        println(
                            "WALKIE $WALKIE_VERSION: 频道状态=$currentChannel 人数=$currentChannelOnlineCount"
                        )
                    }

                    WalkieService.ACTION_CHANNEL_DELETED -> {

                        val deletedChannel =
                            intent.getStringExtra(
                                WalkieService.EXTRA_DELETED_CHANNEL
                            )

                        println(
                            "WALKIE $WALKIE_VERSION: 频道删除=$deletedChannel"
                        )

                        if (
                            deletedChannel.isNullOrBlank() ||
                            deletedChannel == currentChannel
                        ) {

                            currentChannel =
                                "public"

                            currentChannelOnlineCount =
                                0

                            currentChannelPrivate =
                                false

                            currentChannelRequirePassword =
                                false
                        }

                        channelMessage =
                            "频道已删除：${deletedChannel ?: ""}"

                        requestChannelList()
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

        enableEdgeToEdge()

        requestPermissionsIfNeeded()

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
            connectionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {

            WalkieTheme {

                WalkieScreen(

                    connected =
                        connectionState,

                    talkStatus =
                        talkStatus,

                    currentChannel =
                        currentChannel,

                    currentChannelOnlineCount =
                        currentChannelOnlineCount,

                    currentChannelPrivate =
                        currentChannelPrivate,

                    channels =
                        channelList,

                    channelMessage =
                        channelMessage,

                    createChannelDialog =
                        createChannelDialog,

                    joinPasswordDialog =
                        joinPasswordDialog,

                    joinPasswordChannel =
                        joinPasswordChannel,

                    joinPassword =
                        joinPassword,

                    deleteChannelDialog =
                        deleteChannelDialog,

                    onConnect = {
                        startWalkieService(it)
                    },

                    onDisconnect = {
                        stopWalkieService()
                    },

                    onRefreshChannels = {
                        requestChannelList()
                    },

                    onJoinChannel = {
                        joinChannelClicked(it)
                    },

                    onOpenCreateChannel = {
                        createChannelDialog = true
                    },

                    onDismissCreateChannel = {
                        createChannelDialog = false
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

                    onDismissJoinPassword = {

                        joinPasswordDialog =
                            false

                        joinPasswordChannel =
                            ""

                        joinPassword =
                            ""
                    },

                    onPasswordChanged = {
                        joinPassword = it
                    },

                    onConfirmJoinPassword = {

                        if (
                            joinPasswordChannel.isNotBlank() &&
                            joinPassword.isNotBlank()
                        ) {

                            joinPasswordDialog =
                                false

                            val channel =
                                joinPasswordChannel

                            val password =
                                joinPassword

                            joinPasswordChannel =
                                ""

                            joinPassword =
                                ""

                            sendJoinChannel(
                                channel,
                                password
                            )
                        }
                    },

                    onOpenDeleteChannel = {

                        if (
                            currentChannel !=
                            "public"
                        ) {

                            deleteChannelDialog =
                                true
                        }
                    },

                    onDismissDeleteChannel = {
                        deleteChannelDialog = false
                    },

                    onConfirmDeleteChannel = {

                        deleteChannelDialog =
                            false

                        deleteCurrentChannel()
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

            return
        }

        requestNotificationPermissionIfNeeded()
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
     * 启动服务
     * ============================================================
     */

    private fun startWalkieService(
        serverIp: String
    ) {

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            requestMicrophone.launch(
                Manifest.permission.RECORD_AUDIO
            )

            return
        }

        val ip =
            serverIp.trim()

        if (ip.isBlank()) {
            return
        }

        println(
            "WALKIE $WALKIE_VERSION: 连接服务器=$ip"
        )

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_START

                putExtra(
                    WalkieService.EXTRA_SERVER_IP,
                    ip
                )
            }

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
    }

    /*
     * ============================================================
     * 断开
     * ============================================================
     */

    private fun stopWalkieService() {

        pttSpeaking = false

        talkStatus =
            WalkieService.TALK_STATUS_NONE

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_STOP
            }

        @Suppress("DEPRECATION")
        startService(
            intent
        )
    }

    /*
     * ============================================================
     * 请求频道列表
     * ============================================================
     */

    private fun requestChannelList() {

        if (!connectionState) {
            return
        }

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_CHANNEL_LIST
            }

        @Suppress("DEPRECATION")
        startService(
            intent
        )
    }

    /*
     * ============================================================
     * 点击频道
     * ============================================================
     */

    private fun joinChannelClicked(
        channel: ChannelUiInfo
    ) {

        if (!connectionState) {
            return
        }

        if (
            channel.name ==
            currentChannel
        ) {
            return
        }

        if (
            channel.requirePassword
        ) {

            joinPasswordChannel =
                channel.name

            joinPassword =
                ""

            joinPasswordDialog =
                true

        } else {

            sendJoinChannel(
                channel.name,
                ""
            )
        }
    }

    /*
     * ============================================================
     * 加入频道
     * ============================================================
     */

    private fun sendJoinChannel(
        channel: String,
        password: String
    ) {

        if (
            !connectionState ||
            channel.isBlank()
        ) {
            return
        }

        pttSpeaking = false

        talkStatus =
            WalkieService.TALK_STATUS_NONE

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

        @Suppress("DEPRECATION")
        startService(
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
        privateChannel: Boolean,
        password: String
    ) {

        if (!connectionState) {
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
            privateChannel &&
            cleanPassword.isBlank()
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
                    name
                )

                putExtra(
                    WalkieService.EXTRA_CHANNEL_PASSWORD,
                    cleanPassword
                )

                putExtra(
                    WalkieService.EXTRA_CHANNEL_PRIVATE,
                    privateChannel
                )
            }

        @Suppress("DEPRECATION")
        startService(
            intent
        )
    }

    /*
     * ============================================================
     * 删除当前频道
     * ============================================================
     */

    private fun deleteCurrentChannel() {

        if (
            !connectionState ||
            currentChannel.isBlank() ||
            currentChannel == "public"
        ) {
            return
        }

        println(
            "WALKIE $WALKIE_VERSION: ★删除频道=$currentChannel★"
        )

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

        @Suppress("DEPRECATION")
        startService(
            intent
        )
    }

    /*
     * ============================================================
     * PTT
     * ============================================================
     */

    private fun startSpeaking() {

        if (!connectionState) {
            return
        }

        pttSpeaking = true

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_SPEAK_START
            }

        @Suppress("DEPRECATION")
        startService(
            intent
        )
    }

    private fun stopSpeaking() {

        pttSpeaking = false

        val intent =
            Intent(
                this,
                WalkieService::class.java
            ).apply {

                action =
                    WalkieService.ACTION_SPEAK_STOP
            }

        @Suppress("DEPRECATION")
        startService(
            intent
        )
    }

    /*
     * ============================================================
     * 解析频道
     * ============================================================
     */

    private fun parseChannelInfo(
        text: String
    ): ChannelUiInfo? {

        val fields =
            text.split(",")

        val name =
            fields.getOrNull(0)
                ?.trim()
                ?: return null

        if (name.isBlank()) {
            return null
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

        val isPrivate =
            type == "PRIVATE"

        return ChannelUiInfo(
            name =
                name,
            onlineCount =
                count,
            isPrivate =
                isPrivate,
            requirePassword =
                isPrivate
        )
    }

    /*
     * ============================================================
     * 更新当前频道
     * ============================================================
     */

    private fun updateCurrentChannelInList() {

        val list =
            channelList.toMutableList()

        val index =
            list.indexOfFirst {
                it.name ==
                        currentChannel
            }

        val info =
            ChannelUiInfo(
                name =
                    currentChannel,
                onlineCount =
                    currentChannelOnlineCount,
                isPrivate =
                    currentChannelPrivate,
                requirePassword =
                    currentChannelRequirePassword
            )

        if (index >= 0) {

            list[index] =
                info

        } else {

            list.add(info)
        }

        channelList =
            list.sortedBy {
                it.name
            }
    }

    override fun onDestroy() {

        try {

            unregisterReceiver(
                connectionReceiver
            )

        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}

data class ChannelUiInfo(
    val name: String,
    val onlineCount: Int = 0,
    val isPrivate: Boolean = false,
    val requirePassword: Boolean = false
) {

    fun displayName(
        current: Boolean
    ): String {

        val mark =
            if (current) {
                "✓ "
            } else {
                ""
            }

        val icon =
            if (isPrivate) {
                "🔒"
            } else {
                "🌐"
            }

        return "$mark$icon $name    👥 ${onlineCount}人"
    }
}

@Composable
fun WalkieScreen(
    connected: Boolean,
    talkStatus: String,
    currentChannel: String,
    currentChannelOnlineCount: Int,
    currentChannelPrivate: Boolean,
    channels: List<ChannelUiInfo>,
    channelMessage: String,
    createChannelDialog: Boolean,

    joinPasswordDialog: Boolean,
    joinPasswordChannel: String,
    joinPassword: String,

    deleteChannelDialog: Boolean,

    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefreshChannels: () -> Unit,
    onJoinChannel: (ChannelUiInfo) -> Unit,

    onOpenCreateChannel: () -> Unit,
    onDismissCreateChannel: () -> Unit,
    onCreateChannel: (
        String,
        Boolean,
        String
    ) -> Unit,

    onDismissJoinPassword: () -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConfirmJoinPassword: () -> Unit,

    onOpenDeleteChannel: () -> Unit,
    onDismissDeleteChannel: () -> Unit,
    onConfirmDeleteChannel: () -> Unit,

    onStartSpeaking: () -> Unit,
    onStopSpeaking: () -> Unit
) {

    var serverIp by remember {
        mutableStateOf(
            "38.146.29.169"
        )
    }

    var pressing by remember {
        mutableStateOf(false)
    }

    var speakingStarted by remember {
        mutableStateOf(false)
    }

    var channelMenuExpanded by remember {
        mutableStateOf(false)
    }

    var newChannelName by remember {
        mutableStateOf("")
    }

    var newChannelPrivate by remember {
        mutableStateOf(false)
    }

    var newChannelPassword by remember {
        mutableStateOf("")
    }

    val currentConnected =
        rememberUpdatedState(
            connected
        )

    val currentTalkStatus =
        rememberUpdatedState(
            talkStatus
        )

    val currentStartSpeaking =
        rememberUpdatedState(
            onStartSpeaking
        )

    val currentStopSpeaking =
        rememberUpdatedState(
            onStopSpeaking
        )

    val talkAllowed =
        talkStatus ==
                WalkieService.TALK_STATUS_ALLOWED

    val requesting =
        talkStatus ==
                WalkieService.TALK_STATUS_REQUESTING

    val busy =
        talkStatus ==
                WalkieService.TALK_STATUS_BUSY

    /*
     * ============================================================
     * 创建频道
     * ============================================================
     */

    if (createChannelDialog) {

        AlertDialog(

            onDismissRequest = {

                newChannelName = ""
                newChannelPrivate = false
                newChannelPassword = ""

                onDismissCreateChannel()
            },

            title = {
                Text("创建频道")
            },

            text = {

                Column {

                    OutlinedTextField(
                        value =
                            newChannelName,

                        onValueChange = {

                            if (
                                it.length <= 24
                            ) {

                                newChannelName =
                                    it
                            }
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine = true,

                        label = {
                            Text("频道名称")
                        }
                    )

                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Text(
                            if (newChannelPrivate) {
                                "🔒 私密频道"
                            } else {
                                "🌐 公开频道"
                            },
                            modifier =
                                Modifier.weight(1f)
                        )

                        OutlinedButton(

                            onClick = {

                                newChannelPrivate =
                                    !newChannelPrivate

                                if (!newChannelPrivate) {
                                    newChannelPassword = ""
                                }
                            }
                        ) {

                            Text(
                                if (newChannelPrivate) {
                                    "改为公开"
                                } else {
                                    "设密码"
                                }
                            )
                        }
                    }

                    if (newChannelPrivate) {

                        Spacer(
                            modifier =
                                Modifier.height(10.dp)
                        )

                        OutlinedTextField(
                            value =
                                newChannelPassword,

                            onValueChange = {

                                if (
                                    it.length <= 32
                                ) {

                                    newChannelPassword =
                                        it
                                }
                            },

                            modifier =
                                Modifier.fillMaxWidth(),

                            singleLine = true,

                            label = {
                                Text("频道密码")
                            }
                        )
                    }
                }
            },

            confirmButton = {

                Button(

                    onClick = {

                        val name =
                            newChannelName.trim()

                        val password =
                            newChannelPassword.trim()

                        if (name.isBlank()) {
                            return@Button
                        }

                        if (
                            newChannelPrivate &&
                            password.isBlank()
                        ) {
                            return@Button
                        }

                        onCreateChannel(
                            name,
                            newChannelPrivate,
                            password
                        )

                        newChannelName = ""
                        newChannelPrivate = false
                        newChannelPassword = ""
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

                    Text("创建")
                }
            },

            dismissButton = {

                TextButton(

                    onClick = {

                        newChannelName = ""
                        newChannelPrivate = false
                        newChannelPassword = ""

                        onDismissCreateChannel()
                    }
                ) {

                    Text("取消")
                }
            }
        )
    }

    /*
     * ============================================================
     * 加入私密频道密码
     * ============================================================
     */

    if (joinPasswordDialog) {

        AlertDialog(

            onDismissRequest = {
                onDismissJoinPassword()
            },

            title = {
                Text("🔒 输入频道密码")
            },

            text = {

                Column {

                    Text(
                        "频道：$joinPasswordChannel",
                        fontWeight =
                            FontWeight.Bold
                    )

                    Spacer(
                        modifier =
                            Modifier.height(10.dp)
                    )

                    OutlinedTextField(

                        value =
                            joinPassword,

                        onValueChange = {
                            onPasswordChanged(it)
                        },

                        modifier =
                            Modifier.fillMaxWidth(),

                        singleLine = true,

                        label = {
                            Text("密码")
                        }
                    )
                }
            },

            confirmButton = {

                Button(

                    onClick = {
                        onConfirmJoinPassword()
                    },

                    enabled =
                        joinPassword.isNotBlank()
                ) {

                    Text("加入")
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        onDismissJoinPassword()
                    }
                ) {

                    Text("取消")
                }
            }
        )
    }

    /*
     * ============================================================
     * 删除频道确认
     * ============================================================
     */

    if (deleteChannelDialog) {

        AlertDialog(

            onDismissRequest = {
                onDismissDeleteChannel()
            },

            title = {
                Text("删除频道")
            },

            text = {

                Text(
                    "确定要删除「$currentChannel」吗？\n\n删除后频道无法恢复，频道内用户会自动回到 public。"
                )
            },

            confirmButton = {

                Button(

                    onClick = {
                        onConfirmDeleteChannel()
                    }
                ) {

                    Text("确定删除")
                }
            },

            dismissButton = {

                TextButton(
                    onClick = {
                        onDismissDeleteChannel()
                    }
                ) {

                    Text("取消")
                }
            }
        )
    }

    /*
     * ============================================================
     * 主界面
     * ============================================================
     */

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp),

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Spacer(
            modifier =
                Modifier.height(28.dp)
        )

        Text(
            text = "WALKIE",
            fontSize = 34.sp,
            fontWeight =
                FontWeight.Bold
        )

        Text(
            text =
                "公网对讲机 V11",
            fontSize = 16.sp,
            color =
                Color.Gray
        )

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )

        /*
         * ========================================================
         * 服务器
         * ========================================================
         */

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(16.dp),

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
                    Modifier.padding(16.dp)
            ) {

                Text(
                    "服务器",
                    fontSize = 18.sp,
                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                OutlinedTextField(

                    value =
                        serverIp,

                    onValueChange = {
                        serverIp = it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    label = {
                        Text("服务器 IP")
                    },

                    singleLine = true
                )

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                Row(

                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    Button(

                        onClick = {
                            onConnect(
                                serverIp.trim()
                            )
                        },

                        modifier =
                            Modifier.weight(1f)
                    ) {

                        Text("连接")
                    }

                    OutlinedButton(

                        onClick = {

                            pressing = false
                            speakingStarted = false

                            onDisconnect()
                        },

                        modifier =
                            Modifier.weight(1f)
                    ) {

                        Text("断开")
                    }
                }
            }
        }

        Spacer(
            modifier =
                Modifier.height(14.dp)
        )

        /*
         * ========================================================
         * 频道
         * ========================================================
         */

        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(16.dp)
        ) {

            Column(
                modifier =
                    Modifier.padding(16.dp)
            ) {

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
                            "当前频道",
                            fontSize = 14.sp,
                            color =
                                Color.Gray
                        )

                        Spacer(
                            modifier =
                                Modifier.height(3.dp)
                        )

                        Text(
                            text =
                                if (
                                    currentChannelPrivate
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

                        Text(
                            text =
                                "在线人数：${currentChannelOnlineCount}人",

                            fontSize =
                                14.sp,

                            color =
                                Color.Gray
                        )
                    }

                    OutlinedButton(

                        onClick = {
                            onRefreshChannels()
                        },

                        enabled =
                            connected
                    ) {

                        Text("刷新")
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(10.dp)
                )

                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    OutlinedButton(

                        onClick = {
                            channelMenuExpanded =
                                true
                        },

                        enabled =
                            connected,

                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Text(
                            "切换频道"
                        )
                    }

                    DropdownMenu(

                        expanded =
                            channelMenuExpanded,

                        onDismissRequest = {
                            channelMenuExpanded =
                                false
                        }
                    ) {

                        if (channels.isEmpty()) {

                            DropdownMenuItem(

                                text = {
                                    Text(
                                        "暂无频道"
                                    )
                                },

                                onClick = {
                                    channelMenuExpanded =
                                        false
                                }
                            )

                        } else {

                            channels.forEach { channel ->

                                DropdownMenuItem(

                                    text = {

                                        Text(
                                            channel.displayName(
                                                channel.name ==
                                                        currentChannel
                                            )
                                        )
                                    },

                                    onClick = {

                                        channelMenuExpanded =
                                            false

                                        onJoinChannel(
                                            channel
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Button(

                    onClick = {
                        onOpenCreateChannel()
                    },

                    enabled =
                        connected,

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        "＋ 创建频道"
                    )
                }

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                OutlinedButton(

                    onClick = {
                        onOpenDeleteChannel()
                    },

                    enabled =
                        connected &&
                                currentChannel != "public",

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        "删除当前频道"
                    )
                }

                if (
                    channelMessage.isNotBlank()
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(7.dp)
                    )

                    Text(
                        text =
                            channelMessage,

                        fontSize =
                            13.sp,

                        color =
                            Color.Gray
                    )
                }
            }
        }

        Spacer(
            modifier =
                Modifier.height(14.dp)
        )

        /*
         * ========================================================
         * 连接状态
         * ========================================================
         */

        Row(
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(

                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(

                            if (
                                connected
                            ) {

                                Color.Green

                            } else {

                                Color.Red
                            }
                        )
            )

            Spacer(
                modifier =
                    Modifier.size(8.dp)
            )

            Text(
                if (
                    connected
                ) {

                    "已连接"

                } else {

                    "未连接"
                }
            )
        }

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        Text(

            text = when {

                !connected ->
                    "等待连接服务器"

                talkAllowed ->
                    "正在讲话..."

                requesting ->
                    "正在抢麦..."

                busy ->
                    "当前有人讲话"

                else ->
                    "按住讲话"
            },

            fontSize =
                19.sp,

            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        /*
         * ========================================================
         * PTT
         * ========================================================
         */

        Box(

            modifier =
                Modifier
                    .size(210.dp)
                    .clip(CircleShape)
                    .background(

                        when {

                            !connected ->
                                Color.Gray

                            talkAllowed ->
                                Color.Red

                            busy ->
                                Color.DarkGray

                            requesting ->
                                Color(0xFFFF9800)

                            pressing ->
                                Color.Red

                            else ->
                                MaterialTheme
                                    .colorScheme
                                    .primary
                        }
                    )
                    .pointerInput(Unit) {

                        awaitEachGesture {

                            var localSpeaking =
                                false

                            try {

                                val down =
                                    awaitFirstDown(
                                        requireUnconsumed =
                                            false
                                    )

                                down.consume()

                                if (
                                    !currentConnected.value
                                ) {
                                    return@awaitEachGesture
                                }

                                if (
                                    currentTalkStatus.value ==
                                    WalkieService.TALK_STATUS_BUSY
                                ) {
                                    return@awaitEachGesture
                                }

                                if (
                                    currentTalkStatus.value ==
                                    WalkieService.TALK_STATUS_ALLOWED
                                ) {
                                    return@awaitEachGesture
                                }

                                pressing =
                                    true

                                speakingStarted =
                                    true

                                localSpeaking =
                                    true

                                currentStartSpeaking.value()

                                println(
                                    "WALKIE V11: ★PTT DOWN★ channel=$currentChannel"
                                )

                                val pointerId =
                                    down.id

                                var finished =
                                    false

                                while (
                                    !finished
                                ) {

                                    val event =
                                        awaitPointerEvent()

                                    val change =
                                        event.changes
                                            .firstOrNull {
                                                it.id ==
                                                        pointerId
                                            }

                                    if (
                                        change == null
                                    ) {

                                        finished =
                                            true

                                        continue
                                    }

                                    if (
                                        !change.pressed
                                    ) {

                                        change.consume()

                                        finished =
                                            true

                                        continue
                                    }

                                    change.consume()
                                }

                            } catch (
                                e: Exception
                            ) {

                                println(
                                    "WALKIE V11: PTT异常=${e.message}"
                                )

                            } finally {

                                pressing =
                                    false

                                if (
                                    localSpeaking &&
                                    speakingStarted
                                ) {

                                    speakingStarted =
                                        false

                                    currentStopSpeaking.value()

                                    println(
                                        "WALKIE V11: ★PTT STOP★"
                                    )
                                }
                            }
                        }
                    },

            contentAlignment =
                Alignment.Center
        ) {

            Text(

                text = when {

                    !connected ->
                        "未连接"

                    talkAllowed ->
                        "松开"

                    requesting ->
                        "抢麦中"

                    busy ->
                        "有人讲话"

                    pressing ->
                        "讲话中"

                    else ->
                        "按住\n讲话"
                },

                fontSize =
                    27.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Color.White,

                textAlign =
                    TextAlign.Center
            )
        }

        Spacer(
            modifier =
                Modifier.height(14.dp)
        )

        Text(
            text =
                "WALKIE V11 · $currentChannel · ${currentChannelOnlineCount}人",

            fontSize =
                14.sp,

            color =
                Color.Gray,

            textAlign =
                TextAlign.Center
        )
    }
}