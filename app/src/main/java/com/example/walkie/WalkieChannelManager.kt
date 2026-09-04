package com.example.walkie

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WalkieChannelManager(
    private val scope: CoroutineScope,

    private val isConnected: () -> Boolean,
    private val isShuttingDown: () -> Boolean,

    private val getCurrentChannel: () -> String,
    private val setCurrentChannel: (String) -> Unit,

    private val getReconnectChannel: () -> String,
    private val setReconnectChannel: (String) -> Unit,

    private val getReconnectChannelPassword: () -> String,
    private val setReconnectChannelPassword: (String) -> Unit,

    private val getCurrentChannelOnlineCount: () -> Int,
    private val setCurrentChannelOnlineCount: (Int) -> Unit,

    private val getCurrentChannelPrivate: () -> Boolean,
    private val setCurrentChannelPrivate: (Boolean) -> Unit,

    private val getCurrentChannelRequirePassword: () -> Boolean,
    private val setCurrentChannelRequirePassword: (Boolean) -> Unit,

    private val getChannelSwitching: () -> Boolean,
    private val setChannelSwitching: (Boolean) -> Unit,

    private val getPendingCreateChannelName: () -> String,
    private val setPendingCreateChannelName: (String) -> Unit,

    private val getPendingCreateChannelPassword: () -> String,
    private val setPendingCreateChannelPassword: (String) -> Unit,

    private val getUserListEmpty: () -> Boolean,

    private val clearUserList: () -> Unit,

    private val getCachedChannelInfoList:
        () -> ArrayList<WalkieService.ChannelInfo>,

    private val setCachedChannelInfoList:
        (ArrayList<WalkieService.ChannelInfo>) -> Unit,

    private val sendMessageAsync:
        (String) -> Unit,

    private val broadcastChannelList:
        () -> Unit,

    private val broadcastChannelStatus:
        (String) -> Unit,

    private val broadcastChannelDeleted:
        (String) -> Unit,

    private val updateCurrentChannelInfo:
        () -> Unit,

    private val resetTalkState:
        () -> Unit,

    private val requestTalkStatusReset:
        () -> Unit,

    private val msgChannelList: String,
    private val msgChannelMembers: String,

    private val talkStatusReleased: String,

    private val logger: (String) -> Unit
) {

    private var channelRefreshJob: Job? = null

    /*
     * ============================================================
     * 请求频道列表
     * ============================================================
     */
    fun requestChannelList() {

        if (
            !isConnected()
        ) {

            return
        }

        sendMessageAsync(
            msgChannelList
        )
    }

    /*
     * ============================================================
     * 频道列表自动刷新
     * ============================================================
     *
     * 原 WalkieService：
     *
     * 每10秒请求一次频道列表。
     *
     * 这里只搬职责，不改变时间和逻辑。
     * ============================================================
     */
    fun startChannelRefreshWorker() {

        if (
            channelRefreshJob?.isActive ==
            true
        ) {

            return
        }

        channelRefreshJob =
            scope.launch {

                while (
                    scope.isActive &&
                    !isShuttingDown()
                ) {

                    delay(
                        10000L
                    )

                    if (
                        isConnected()
                    ) {

                        requestChannelList()
                    }
                }
            }
    }

    fun stopChannelRefreshWorker() {

        channelRefreshJob?.cancel()

        channelRefreshJob =
            null
    }

    /*
     * ============================================================
     * 解析服务器 CHANNEL_LIST
     * ============================================================
     */
    fun handleChannelList(
        text: String
    ) {

        val content =
            text.substringAfter(
                "$msgChannelList:",
                ""
            )

        val result =
            ArrayList<WalkieService.ChannelInfo>()

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
                    WalkieService.ChannelInfo(
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

        val currentChannel =
            getCurrentChannel()

        val currentOnlineCount =
            getCurrentChannelOnlineCount()

        val currentPrivate =
            getCurrentChannelPrivate()

        if (
            !result.any {
                it.name ==
                        currentChannel
            }
        ) {

            result.add(
                WalkieService.ChannelInfo(
                    name =
                        currentChannel,
                    onlineCount =
                        currentOnlineCount,
                    isPrivate =
                        currentPrivate
                )
            )
        }

        setCachedChannelInfoList(
            ArrayList(
                result
                    .distinctBy {
                        it.name
                    }
                    .sortedBy {
                        it.name
                    }
            )
        )

        /*
         * 当前频道的真实在线人数优先。
         */
        getCachedChannelInfoList()
            .firstOrNull {
                it.name ==
                        currentChannel
            }?.let {

                setCurrentChannelPrivate(
                    it.isPrivate
                )

                setCurrentChannelRequirePassword(
                    it.isPrivate
                )

                /*
                 * 只有没有真实成员列表时，
                 * 才使用 CHANNEL_LIST 的人数。
                 */
                if (
                    getUserListEmpty()
                ) {

                    setCurrentChannelOnlineCount(
                        it.onlineCount
                    )
                }
            }

        broadcastChannelList()

        broadcastChannelStatus(
            "频道：$currentChannel，在线 ${getCurrentChannelOnlineCount()} 人"
        )
    }

    /*
     * ============================================================
     * 创建频道
     * ============================================================
     */
    fun createChannel(
        channel: String,
        password: String,
        privateChannel: Boolean
    ) {

        if (
            !isConnected()
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

        setPendingCreateChannelName(
            name
        )

        setPendingCreateChannelPassword(
            cleanPassword
        )

        setChannelSwitching(
            true
        )

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

    /*
     * ============================================================
     * 加入频道
     * ============================================================
     */
    fun joinChannel(
        channel: String,
        password: String
    ) {

        if (
            !isConnected()
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

            setReconnectChannelPassword(
                cleanPassword
            )
        }

        val currentChannel =
            getCurrentChannel()

        if (
            name ==
            currentChannel
        ) {

            setChannelSwitching(
                false
            )

            broadcastChannelStatus(
                "当前已经在频道：$currentChannel，在线 ${getCurrentChannelOnlineCount()} 人"
            )

            return
        }

        /*
         * 换频道时先停止当前讲话状态。
         */
        resetTalkState()

        requestTalkStatusReset()

        clearUserList()

        setChannelSwitching(
            true
        )

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

    /*
     * ============================================================
     * 服务器确认进入频道
     * ============================================================
     */
    fun handleChannelJoined(
        text: String
    ) {

        val content =
            text.substringAfter(
                "WALKIE_CHANNEL_JOINED:",
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

        setCurrentChannel(
            name
        )

        setReconnectChannel(
            name
        )

        setCurrentChannelOnlineCount(
            count
        )

        val isPrivate =
            type ==
                    "PRIVATE"

        setCurrentChannelPrivate(
            isPrivate
        )

        setCurrentChannelRequirePassword(
            isPrivate
        )

        if (
            !isPrivate &&
            name ==
            "public"
        ) {

            setReconnectChannelPassword(
                ""
            )
        }

        setChannelSwitching(
            false
        )

        resetTalkState()

        requestTalkStatusReset()

        clearUserList()

        setPendingCreateChannelName(
            ""
        )

        setPendingCreateChannelPassword(
            ""
        )

        updateCurrentChannelInfo()

        broadcastChannelStatus(
            "已进入频道：$name，在线 ${getCurrentChannelOnlineCount()} 人"
        )

        sendMessageAsync(
            msgChannelMembers
        )

        requestChannelList()
    }

    /*
     * ============================================================
     * 服务器确认创建频道
     * ============================================================
     */
    fun handleChannelCreated(
        text: String
    ) {

        val content =
            text.substringAfter(
                "WALKIE_CHANNEL_CREATED:",
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

        setCurrentChannelPrivate(
            privateChannel
        )

        setCurrentChannelRequirePassword(
            privateChannel
        )

        joinChannel(
            name,
            getPendingCreateChannelPassword()
        )
    }

    /*
     * ============================================================
     * 频道删除
     * ============================================================
     */
    fun handleChannelDeleted(
        text: String
    ) {

        val deletedChannel =
            text.substringAfter(
                "WALKIE_CHANNEL_DELETED:",
                ""
            )
                .trim()

        if (
            deletedChannel ==
            getCurrentChannel()
        ) {

            setCurrentChannel(
                "public"
            )

            setReconnectChannel(
                "public"
            )

            setReconnectChannelPassword(
                ""
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

            setChannelSwitching(
                false
            )

            resetTalkState()

            requestTalkStatusReset()

            clearUserList()

            broadcastChannelStatus(
                "频道已删除：$deletedChannel，已返回 public"
            )

            if (
                isConnected()
            ) {

                sendMessageAsync(
                    msgChannelMembers
                )
            }
        }

        broadcastChannelDeleted(
            deletedChannel
        )

        requestChannelList()
    }

    /*
     * ============================================================
     * 频道错误
     * ============================================================
     */
    fun handleChannelError(
        text: String
    ) {

        val error =
            text.substringAfter(
                "WALKIE_CHANNEL_ERROR:",
                "UNKNOWN"
            )
                .trim()

        setChannelSwitching(
            false
        )

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

    /*
     * ============================================================
     * 离开频道
     * ============================================================
     */
    fun handleChannelLeft(
        text: String
    ) {

        val content =
            text.substringAfter(
                "WALKIE_CHANNEL_LEFT:",
                "public"
            )

        val fields =
            content.split(":")

        val newChannel =
            fields.getOrNull(0)
                ?.trim()
                ?.ifBlank {
                    "public"
                }
                ?: "public"

        val count =
            fields.getOrNull(1)
                ?.trim()
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0

        setCurrentChannel(
            newChannel
        )

        setCurrentChannelOnlineCount(
            count
        )

        setCurrentChannelPrivate(
            false
        )

        setCurrentChannelRequirePassword(
            false
        )

        setChannelSwitching(
            false
        )

        clearUserList()

        if (
            newChannel ==
            "public"
        ) {

            setReconnectChannel(
                "public"
            )

            setReconnectChannelPassword(
                ""
            )
        }

        updateCurrentChannelInfo()

        broadcastChannelStatus(
            "当前频道：$newChannel，在线 ${getCurrentChannelOnlineCount()} 人"
        )

        if (
            isConnected()
        ) {

            sendMessageAsync(
                msgChannelMembers
            )
        }
    }

    /*
     * ============================================================
     * 更新当前频道在频道列表里的信息
     * ============================================================
     */
    fun updateCurrentChannelInfo() {

        val list =
            getCachedChannelInfoList()
                .toMutableList()

        val currentChannel =
            getCurrentChannel()

        val info =
            WalkieService.ChannelInfo(
                name =
                    currentChannel,
                onlineCount =
                    getCurrentChannelOnlineCount(),
                isPrivate =
                    getCurrentChannelPrivate()
            )

        val index =
            list.indexOfFirst {
                it.name ==
                        currentChannel
            }

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

        setCachedChannelInfoList(
            ArrayList(
                list.sortedBy {
                    it.name
                }
            )
        )

        broadcastChannelList()
    }

    /*
     * ============================================================
     * 删除频道请求
     * ============================================================
     */
    fun deleteChannel(
        channel: String
    ) {

        if (
            !isConnected()
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
}