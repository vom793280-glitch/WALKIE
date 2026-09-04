package com.example.walkie

import java.net.DatagramSocket

class WalkieMessageDispatcher(
    private val msgConnected: String,
    private val msgKeepAlive: String,
    private val msgUserOk: String,
    private val msgUserStatus: String,
    private val msgChannelMembers: String,
    private val msgChannelList: String,
    private val msgChannelJoined: String,
    private val msgChannelCreated: String,
    private val msgChannelDeleted: String,
    private val msgChannelError: String,
    private val msgChannelLeft: String,
    private val msgNetPong: String,

    private val isConnectionGenerationCurrent: (
        Long
    ) -> Boolean,

    private val onConnected: (
        Long,
        DatagramSocket
    ) -> Unit,

    private val onNetworkPong: (
        String
    ) -> Unit,

    private val onUserOk: (
        String
    ) -> Unit,

    private val onUserStatus: (
        String
    ) -> Unit,

    private val onChannelMembers: (
        String
    ) -> Unit,

    private val onChannelList: (
        String
    ) -> Unit,

    private val onChannelJoined: (
        String
    ) -> Unit,

    private val onChannelCreated: (
        String
    ) -> Unit,

    private val onChannelDeleted: (
        String
    ) -> Unit,

    private val onChannelError: (
        String
    ) -> Unit,

    private val onChannelLeft: (
        String
    ) -> Unit,

    private val handleTalkMessage: (
        String,
        Long
    ) -> Boolean
) {

    fun dispatch(
        text: String,
        generation: Long,
        socket: DatagramSocket
    ): Boolean {

        /*
         * ============================================================
         * UDP连接成功
         * ============================================================
         */

        if (
            text ==
            msgConnected
        ) {

            if (
                !isConnectionGenerationCurrent(
                    generation
                )
            ) {

                return true
            }

            onConnected(
                generation,
                socket
            )

            return true
        }

        /*
         * ============================================================
         * KEEPALIVE应答
         * ============================================================
         */

        if (
            text ==
            msgKeepAlive
        ) {

            return true
        }

        /*
         * ============================================================
         * 网络PING/PONG
         * ============================================================
         */

        if (
            text.startsWith(
                "$msgNetPong:"
            )
        ) {

            onNetworkPong(
                text
            )

            return true
        }

        /*
         * ============================================================
         * 用户
         * ============================================================
         */

        if (
            text.startsWith(
                "$msgUserOk:"
            )
        ) {

            onUserOk(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgUserStatus:"
            )
        ) {

            onUserStatus(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgChannelMembers:"
            )
        ) {

            onChannelMembers(
                text
            )

            return true
        }

        /*
         * ============================================================
         * 频道
         * ============================================================
         */

        if (
            text.startsWith(
                "$msgChannelList:"
            )
        ) {

            onChannelList(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgChannelJoined:"
            )
        ) {

            onChannelJoined(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgChannelCreated:"
            )
        ) {

            onChannelCreated(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgChannelDeleted:"
            )
        ) {

            onChannelDeleted(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgChannelError:"
            )
        ) {

            onChannelError(
                text
            )

            return true
        }

        if (
            text.startsWith(
                "$msgChannelLeft:"
            )
        ) {

            onChannelLeft(
                text
            )

            return true
        }

        /*
         * ============================================================
         * 抢麦
         * ============================================================
         */

        if (
            handleTalkMessage(
                text,
                generation
            )
        ) {

            return true
        }

        /*
         * ============================================================
         * 其他 WALKIE_ 控制包
         * ============================================================
         */

        if (
            text.startsWith(
                "WALKIE_"
            )
        ) {

            return true
        }

        /*
         * 不是控制消息。
         *
         * 返回 false：
         * 继续交给 WalkieAudioReceiver。
         */

        return false
    }
}