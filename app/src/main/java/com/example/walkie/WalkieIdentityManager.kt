package com.example.walkie

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.ArrayList
import java.util.UUID

class WalkieIdentityManager(
    private val context: Context,
    private val scope: CoroutineScope,

    private val devicePrefsName: String,
    private val deviceIdKey: String,

    private val profilePrefsName: String,
    private val nicknameKey: String,

    private val actionMyUserInfo: String,
    private val actionUserList: String,

    private val extraMyUserId: String,
    private val extraMyUsername: String,
    private val extraUserList: String,

    private val isConnected: () -> Boolean,
    private val isShuttingDown: () -> Boolean,

    private val sendMessageNow: (String) -> Unit,

    private val logger: (String) -> Unit
) {

    private lateinit var devicePreferences: SharedPreferences
    private lateinit var profilePreferences: SharedPreferences

    var deviceId: String = ""
        private set

    var nickname: String = ""
        private set

    var myUserId: String = ""
        private set

    var myUsername: String = ""
        private set

    val currentUserList:
            ArrayList<WalkieService.UserInfo> =
        ArrayList()

    fun initialize() {

        devicePreferences =
            context.getSharedPreferences(
                devicePrefsName,
                Context.MODE_PRIVATE
            )

        profilePreferences =
            context.getSharedPreferences(
                profilePrefsName,
                Context.MODE_PRIVATE
            )

        deviceId =
            loadOrCreateDeviceId()

        nickname =
            loadNickname()
    }

    fun setDeviceId(
        value: String
    ) {

        val clean =
            value.trim()

        if (
            clean.isBlank()
        ) {
            return
        }

        deviceId =
            clean

        try {

            devicePreferences
                .edit()
                .putString(
                    deviceIdKey,
                    deviceId
                )
                .apply()

        } catch (_: Exception) {
        }
    }

    fun setNickname(
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
                    nicknameKey,
                    clean
                )
                .apply()

        } catch (_: Exception) {
        }

        logger(
            "设置昵称=$nickname"
        )

        broadcastMyUserInfo()

        if (
            isConnected()
        ) {

            sendLoginAsync()
        }
    }

    fun loadOrCreateDeviceId():
            String {

        val saved =
            try {

                devicePreferences
                    .getString(
                        deviceIdKey,
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
                    deviceIdKey,
                    newId
                )
                .apply()

        } catch (_: Exception) {
        }

        return newId
    }

    fun loadNickname():
            String {

        return try {

            profilePreferences
                .getString(
                    nicknameKey,
                    ""
                )
                ?.trim()
                ?.take(20)
                ?: ""

        } catch (_: Exception) {

            ""
        }
    }

    fun cleanNickname(
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

    fun deviceLogId():
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

    fun sendLoginAsync() {

        if (
            !isConnected() ||
            isShuttingDown()
        ) {

            return
        }

        scope.launch {

            sendLoginNow()
        }
    }

    fun sendLoginNow() {

        val currentNickname =
            cleanNickname(
                nickname
            )

        if (
            currentNickname.isBlank()
        ) {

            sendMessageNow(
                "WALKIE_LOGIN:$deviceId"
            )

        } else {

            sendMessageNow(
                "WALKIE_LOGIN:$deviceId:$currentNickname"
            )
        }

        logger(
            "已发送登录昵称=$currentNickname"
        )
    }

    fun handleUserOk(
        text: String
    ) {

        val payload =
            text.substringAfter(
                "WALKIE_USER_OK:",
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
                            nicknameKey,
                            myUsername
                        )
                        .apply()

                } catch (_: Exception) {
                }
            }
        }

        broadcastMyUserInfo()

        logger(
            "USER_OK " +
                    "id=$myUserId " +
                    "username=$myUsername " +
                    "channel=${parts.getOrNull(2).orEmpty()}"
        )
    }

    fun handleUserStatus(
        text: String
    ) {

        val payload =
            text.substringAfter(
                "WALKIE_USER_STATUS:",
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
                                nicknameKey,
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

    fun broadcastMyUserInfo() {

        val intent =
            Intent(
                actionMyUserInfo
            )

        intent.setPackage(
            context.packageName
        )

        intent.putExtra(
            extraMyUserId,
            myUserId
        )

        intent.putExtra(
            extraMyUsername,
            if (
                myUsername.isNotBlank()
            ) {

                myUsername

            } else {

                nickname
            }
        )

        context.sendBroadcast(
            intent
        )
    }

    fun broadcastUserList() {

        val intent =
            Intent(
                actionUserList
            )

        intent.setPackage(
            context.packageName
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
            extraUserList,
            list
        )

        context.sendBroadcast(
            intent
        )
    }

    fun clearUserList() {

        currentUserList.clear()

        broadcastUserList()
    }

    fun clearServerUserIdentity() {

        myUserId =
            ""

        myUsername =
            ""

        broadcastMyUserInfo()
    }
}