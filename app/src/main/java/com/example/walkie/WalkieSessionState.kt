package com.example.walkie

import java.util.ArrayList

/**
 * WALKIE 会话状态。
 *
 * 这里只负责保存身份、频道、用户列表等状态。
 * 不负责网络、不负责音频、不负责业务动作。
 *
 * 当前阶段：
 * 只拆结构，不改变原有业务逻辑。
 */
class WalkieSessionState {

    var deviceId: String = ""

    var nickname: String = ""

    var myUserId: String = ""

    var myUsername: String = ""

    var isConnected: Boolean = false

    var isNetworkAvailable: Boolean = true

    var currentChannel: String = "public"

    var reconnectChannel: String = ""

    var reconnectChannelPassword: String = ""

    var currentChannelOnlineCount: Int = 0

    var currentChannelPrivate: Boolean = false

    var currentChannelRequirePassword: Boolean = false

    var channelSwitching: Boolean = false

    var pendingCreateChannelName: String = ""

    var pendingCreateChannelPassword: String = ""

    var currentUserList: ArrayList<WalkieService.UserInfo> =
        ArrayList()

    var cachedChannelInfoList: ArrayList<WalkieService.ChannelInfo> =
        ArrayList()

    var talkRequesting: Boolean = false

    var talkAllowed: Boolean = false

    var isSpeaking: Boolean = false

    var shuttingDown: Boolean = false
}