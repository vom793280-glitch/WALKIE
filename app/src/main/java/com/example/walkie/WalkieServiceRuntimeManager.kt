package com.example.walkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class WalkieServiceRuntimeManager(
    private val service: Service,
    private val context: Context,

    private val channelId: String,
    private val notificationId: Int,

    private val notificationTitle: String,
    private val notificationTextProvider: () -> String,

    private val logger: (String) -> Unit
) {

    private var wakeLock:
            PowerManager.WakeLock? =
        null

    fun createNotification():
            Notification {

        return NotificationCompat.Builder(
            context,
            channelId
        )
            .setContentTitle(
                notificationTitle
            )
            .setContentText(
                notificationTextProvider()
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

    fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "WALKIE 对讲服务",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "保持 WALKIE 后台运行"

            try {

                val manager =
                    service.getSystemService(
                        NotificationManager::class.java
                    )

                manager?.createNotificationChannel(
                    channel
                )

            } catch (
                _:
                Exception
            ) {
            }
        }
    }

    fun startForeground() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                service.startForeground(
                    notificationId,
                    createNotification(),
                    android.content.pm.ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            android.content.pm.ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )

            } else {

                service.startForeground(
                    notificationId,
                    createNotification()
                )
            }

            logger(
                "前台服务启动成功"
            )

        } catch (
            e:
            Exception
        ) {

            logger(
                "前台服务启动失败=${e.message}"
            )
        }
    }

    fun updateNotification() {

        try {

            val manager =
                service.getSystemService(
                    NotificationManager::class.java
                )

            manager?.notify(
                notificationId,
                createNotification()
            )

        } catch (
            _:
            Exception
        ) {
        }
    }

    fun acquireWakeLock() {

        try {

            val powerManager =
                service.getSystemService(
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

            logger(
                "WakeLock已开启"
            )

        } catch (
            e:
            Exception
        ) {

            logger(
                "WakeLock失败=${e.message}"
            )
        }
    }

    fun releaseWakeLock() {

        try {

            if (
                wakeLock?.isHeld ==
                true
            ) {

                wakeLock?.release()
            }

        } catch (
            _:
            Exception
        ) {
        }

        wakeLock =
            null
    }

    fun stop() {

        releaseWakeLock()
    }
}