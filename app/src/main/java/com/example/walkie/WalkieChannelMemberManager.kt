package com.example.walkie

class WalkieChannelMemberManager(
    private val msgChannelMembers: String,

    private val getCurrentChannel: () -> String,

    private val getMyUserId: () -> String,

    private val onMembersUpdated: (
        ArrayList<WalkieService.UserInfo>
    ) -> Unit,

    private val onEmptyMembers: () -> Unit,

    private val onMembersCountUpdated: (
        String,
        Int
    ) -> Unit,

    private val logger: (String) -> Unit
) {

    fun handle(
        text: String
    ) {

        val payload =
            text.substringAfter(
                "$msgChannelMembers:",
                ""
            )

        if (
            payload.isBlank()
        ) {

            onEmptyMembers()

            logger(
                "收到空成员列表，当前频道在线人数=0"
            )

            return
        }

        val firstColon =
            payload.indexOf(
                ':'
            )

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

        val currentChannel =
            getCurrentChannel()

        if (
            channelName.isBlank() ||
            channelName !=
            currentChannel
        ) {

            return
        }

        val memberText =
            payload.substring(
                firstColon + 1
            )

        val result =
            ArrayList<WalkieService.UserInfo>()

        if (
            memberText.isNotBlank()
        ) {

            for (
            item in
            memberText.split(";")
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
                    WalkieService.UserInfo(
                        userId =
                            id,

                        username =
                            username
                    )
                )
            }
        }

        val myUserId =
            getMyUserId()

        val distinctResult =
            result
                .distinctBy {
                    it.userId
                }
                .sortedWith(
                    Comparator {
                            left,
                            right ->

                        val leftPriority =
                            if (
                                left.userId ==
                                myUserId
                            ) {
                                0
                            } else {
                                1
                            }

                        val rightPriority =
                            if (
                                right.userId ==
                                myUserId
                            ) {
                                0
                            } else {
                                1
                            }

                        when {

                            leftPriority !=
                                    rightPriority ->

                                leftPriority
                                    .compareTo(
                                        rightPriority
                                    )

                            else ->

                                left.username
                                    .compareTo(
                                        right.username
                                    )
                        }
                    }
                )

        onMembersUpdated(
            ArrayList(
                distinctResult
            )
        )

        onMembersCountUpdated(
            channelName,
            distinctResult.size
        )
    }
}