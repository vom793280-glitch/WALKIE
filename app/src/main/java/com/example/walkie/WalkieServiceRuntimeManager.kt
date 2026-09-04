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

    /*
     * ============================================================
     * Android 17 / Target 37 后台音频保护
     * ============================================================
     *
     * 正常情况下：
     *
     *   MICROPHONE + MEDIA_PLAYBACK
     *
     * 这样前台启动时既支持讲话，又支持后台收音。
     *
     * 但是 Android 17 对 Target 37 的后台音频增加了
     * While-In-Use(WIU) 限制。
     *
     * 当 Service 因 START_STICKY 等原因在后台被系统重新创建时，
     * 此时再次以 MICROPHONE 类型调用 startForeground()，
     * 可能被系统拒绝。
     *
     * WALKIE 的核心需求仍然是：
     *
     *   锁屏 / 后台继续收听
     *
     * 收音功能只在用户按下PTT时才需要 MICROPHONE 类型。
     *
     * 因此这里增加安全降级：
     *
     *   第一次：MICROPHONE + MEDIA_PLAYBACK
     *          ↓失败
     *   第二次：MEDIA_PLAYBACK
     *
     * 这样不会因为“麦克风 FGS 的后台 WIU 限制”
     * 导致整个后台播放服务失去前台服务保护。
     * ============================================================
     */

    private var foregroundStarted =
        false

    private var foregroundUsesMicrophone =
        false

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

        if (
            foregroundStarted
        ) {

            return
        }

        /*
         * ========================================================
         * 第一阶段：
         *
         * 正常尝试：
         *
         * MICROPHONE + MEDIA_PLAYBACK
         *
         * 前台启动时保持原有能力。
         * ========================================================
         */

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

                foregroundUsesMicrophone =
                    true

            } else {

                service.startForeground(
                    notificationId,
                    createNotification()
                )

                foregroundUsesMicrophone =
                    false
            }

            foregroundStarted =
                true

            logger(
                "前台服务启动成功 " +
                        "mode=" +
                        if (
                            foregroundUsesMicrophone
                        ) {
                            "microphone+mediaPlayback"
                        } else {
                            "legacy"
                        }
            )

            return

        } catch (
            e:
            SecurityException
        ) {

            /*
             * Android 17 / Target 37：
             *
             * 后台重新创建 Service 时，
             * MICROPHONE FGS 可能因为 WIU 限制失败。
             *
             * 立即降级成纯 MEDIA_PLAYBACK FGS。
             */

            logger(
                "前台服务MICROPHONE模式被系统拒绝=" +
                        e.message +
                        "，尝试降级MEDIA_PLAYBACK"
            )

        } catch (
            e:
            IllegalArgumentException
        ) {

            logger(
                "前台服务类型参数异常=" +
                        e.message +
                        "，尝试降级MEDIA_PLAYBACK"
            )

        } catch (
            e:
            Exception
        ) {

            logger(
                "前台服务启动异常=" +
                        e.message +
                        "，尝试降级MEDIA_PLAYBACK"
            )
        }

        /*
         * ========================================================
         * 第二阶段：
         *
         * MEDIA_PLAYBACK 安全降级
         *
         * 重点保证：
         *
         * 锁屏
         * 后台
         * Service重建
         *
         * 仍然保留前台服务保护。
         * ========================================================
         */

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                service.startForeground(
                    notificationId,
                    createNotification(),
                    android.content.pm.ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )

            } else {

                service.startForeground(
                    notificationId,
                    createNotification()
                )
            }

            foregroundUsesMicrophone =
                false

            foregroundStarted =
                true

            logger(
                "前台服务降级启动成功 " +
                        "mode=mediaPlayback"
            )

        } catch (
            e:
            Exception
        ) {

            foregroundStarted =
                false

            foregroundUsesMicrophone =
                false

            logger(
                "前台服务降级仍失败=" +
                        e.message
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

        foregroundStarted =
            false

        foregroundUsesMicrophone =
            false
    }
}