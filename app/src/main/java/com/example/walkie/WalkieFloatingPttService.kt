package com.example.walkie

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/*
 * ============================================================
 * WALKIE V24.9.0
 *
 * Android 全局悬浮 PTT
 *
 * 功能：
 * 1. 全局显示
 * 2. 按住 = 开始讲话
 * 3. 松开 = 停止讲话
 * 4. 可以拖动
 * 5. 松手自动吸附左右边缘
 * 6. 显示离线 / 待机 / 抢麦 / 讲话 / 忙线
 *
 * 外观：
 * 1. 主按钮半透明
 * 2. 不同状态使用不同半透明颜色
 * 3. 状态文字同样保持透明效果
 *
 * 本服务不直接录音。
 *
 * 真正的录音、编码、UDP发送：
 * 仍然全部由 WalkieService 完成。
 *
 * ============================================================
 */

class WalkieFloatingPttService : Service() {

    companion object {

        const val ACTION_SHOW =
            "com.example.walkie.FLOAT_SHOW"

        const val ACTION_HIDE =
            "com.example.walkie.FLOAT_HIDE"

        const val ACTION_OPEN_SETTINGS =
            "com.example.walkie.FLOAT_OPEN_SETTINGS"

        private const val WINDOW_NAME =
            "WALKIE_FLOAT_PTT"

        private const val FLOAT_SIZE_DP =
            76

        private const val EDGE_MARGIN_DP =
            8

        private const val DRAG_THRESHOLD_DP =
            8

        private const val PREFS_NAME =
            "walkie_floating_ptt"

        private const val PREF_X =
            "x"

        private const val PREF_Y =
            "y"

        private const val DEFAULT_X =
            0

        private const val DEFAULT_Y =
            420

        /*
         * ========================================================
         * 悬浮按钮透明度
         *
         * 255 = 完全不透明
         * 170 = 半透明
         *
         * 现在统一使用 170。
         * ========================================================
         */
        private const val FLOAT_ALPHA =
            130
    }

    /*
     * ============================================================
     * 系统悬浮窗
     * ============================================================
     */

    private lateinit var windowManager:
            WindowManager

    private var floatingRoot:
            FrameLayout? =
        null

    private var floatingButton:
            TextView? =
        null

    private var statusText:
            TextView? =
        null

    private var windowParams:
            WindowManager.LayoutParams? =
        null

    /*
     * ============================================================
     * 当前状态
     * ============================================================
     */

    private var connected =
        false

    private var talkStatus =
        WalkieService.TALK_STATUS_NONE

    private var touching =
        false

    private var dragging =
        false

    /*
     * ============================================================
     * 手指位置
     * ============================================================
     */

    private var downRawX =
        0f

    private var downRawY =
        0f

    private var startX =
        0

    private var startY =
        0

    /*
     * ============================================================
     * 状态广播
     * ============================================================
     */

    private val walkieReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (
                    intent?.action
                ) {

                    WalkieService.ACTION_CONNECTION_STATUS -> {

                        connected =
                            intent.getBooleanExtra(
                                WalkieService.EXTRA_CONNECTED,
                                false
                            )

                        if (!connected) {

                            touching =
                                false

                            sendSpeakStop()

                            talkStatus =
                                WalkieService.TALK_STATUS_NONE
                        }

                        updateUi()
                    }

                    WalkieService.ACTION_TALK_STATUS -> {

                        talkStatus =
                            intent.getStringExtra(
                                WalkieService.EXTRA_TALK_STATUS
                            )
                                ?: WalkieService.TALK_STATUS_NONE

                        /*
                         * 服务器明确告诉我们：
                         * 抢麦失败 / 释放 / 忙线时，
                         * 强制结束本地按压状态。
                         */
                        if (
                            talkStatus ==
                            WalkieService.TALK_STATUS_BUSY ||
                            talkStatus ==
                            WalkieService.TALK_STATUS_RELEASED ||
                            talkStatus ==
                            WalkieService.TALK_STATUS_NONE
                        ) {

                            touching =
                                false
                        }

                        updateUi()
                    }
                }
            }
        }

    /*
     * ============================================================
     * Service 生命周期
     * ============================================================
     */

    override fun onCreate() {

        super.onCreate()

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        registerWalkieReceiver()

        if (
            Settings.canDrawOverlays(
                this
            )
        ) {

            showFloatingWindow()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {

            ACTION_SHOW -> {

                if (
                    Settings.canDrawOverlays(
                        this
                    )
                ) {

                    showFloatingWindow()
                }
            }

            ACTION_HIDE -> {

                hideFloatingWindow()
            }

            ACTION_OPEN_SETTINGS -> {

                openOverlaySettings()
            }
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    override fun onDestroy() {

        touching =
            false

        sendSpeakStop()

        hideFloatingWindow()

        try {

            unregisterReceiver(
                walkieReceiver
            )

        } catch (
            ignored: Exception
        ) {
        }

        super.onDestroy()
    }

    /*
     * ============================================================
     * 注册广播
     * ============================================================
     */

    private fun registerWalkieReceiver() {

        val filter =
            IntentFilter().apply {

                addAction(
                    WalkieService.ACTION_CONNECTION_STATUS
                )

                addAction(
                    WalkieService.ACTION_TALK_STATUS
                )
            }

        if (
            Build.VERSION.SDK_INT >= 33
        ) {

            registerReceiver(
                walkieReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress(
                "UnspecifiedRegisterReceiverFlag"
            )
            registerReceiver(
                walkieReceiver,
                filter
            )
        }
    }

    /*
     * ============================================================
     * 打开系统悬浮窗权限页面
     * ============================================================
     */

    private fun openOverlaySettings() {

        try {

            val intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                ).apply {

                    data =
                        android.net.Uri.parse(
                            "package:$packageName"
                        )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            startActivity(
                intent
            )

        } catch (
            error: Exception
        ) {

            /*
             * 部分系统的设置页面不支持 package Uri，
             * 退回到通用页面。
             */

            try {

                val intent =
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                startActivity(
                    intent
                )

            } catch (
                ignored: Exception
            ) {
            }
        }
    }

    /*
     * ============================================================
     * 创建悬浮窗
     * ============================================================
     */

    private fun showFloatingWindow() {

        if (
            floatingRoot != null
        ) {

            updateUi()

            return
        }

        if (
            !Settings.canDrawOverlays(
                this
            )
        ) {

            return
        }

        val size =
            dp(
                FLOAT_SIZE_DP
            )

        val params =
            WindowManager.LayoutParams().apply {

                width =
                    size

                height =
                    size

                /*
                 * Android 8.0+
                 * 普通应用使用 TYPE_APPLICATION_OVERLAY。
                 */
                type =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.O
                    ) {

                        WindowManager.LayoutParams
                            .TYPE_APPLICATION_OVERLAY

                    } else {

                        @Suppress(
                            "DEPRECATION"
                        )
                        WindowManager.LayoutParams
                            .TYPE_PHONE
                    }

                flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                format =
                    PixelFormat.TRANSLUCENT

                gravity =
                    Gravity.TOP or
                            Gravity.START

                x =
                    loadX(
                        size
                    )

                y =
                    loadY(
                        size
                    )
            }

        windowParams =
            params

        /*
         * ========================================================
         * 外层
         * ========================================================
         */

        val root =
            FrameLayout(
                this
            )

        /*
         * ========================================================
         * 主圆形按钮
         * ========================================================
         */

        val button =
            TextView(
                this
            ).apply {

                text =
                    "🎙"

                gravity =
                    Gravity.CENTER

                textSize =
                    28f

                setTextColor(
                    Color.WHITE
                )

                typeface =
                    Typeface.DEFAULT_BOLD

                /*
                 * 初始颜色：
                 * 半透明蓝色
                 */
                background =
                    createCircleBackground(
                        Color.rgb(
                            32,
                            126,
                            255
                        )
                    )

                elevation =
                    dp(
                        8
                    )
                        .toFloat()
            }

        /*
         * ========================================================
         * 状态文字
         * ========================================================
         */

        val status =
            TextView(
                this
            ).apply {

                text =
                    "待机"

                gravity =
                    Gravity.CENTER

                textSize =
                    9f

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

                background =
                    GradientDrawable().apply {

                        /*
                         * 状态小标签也做半透明。
                         */
                        setColor(
                            Color.argb(
                                145,
                                0,
                                0,
                                0
                            )
                        )

                        cornerRadius =
                            dp(8)
                                .toFloat()
                    }

                setPadding(
                    dp(4),
                    dp(1),
                    dp(4),
                    dp(1)
                )
            }

        /*
         * ========================================================
         * 状态放在左下角
         * ========================================================
         */

        val statusParams =
            FrameLayout.LayoutParams(
                dp(34),
                dp(18)
            ).apply {

                gravity =
                    Gravity.BOTTOM or
                            Gravity.START

                leftMargin =
                    dp(2)

                bottomMargin =
                    dp(2)
            }

        root.addView(
            button,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            status,
            statusParams
        )

        /*
         * ========================================================
         * 点击 / 长按 / 拖动
         * ========================================================
         */

        button.setOnTouchListener(
            createTouchListener()
        )

        floatingRoot =
            root

        floatingButton =
            button

        statusText =
            status

        try {

            windowManager.addView(
                root,
                params
            )

            updateUi()

        } catch (
            error: Exception
        ) {

            floatingRoot =
                null

            floatingButton =
                null

            statusText =
                null

            windowParams =
                null

            android.util.Log.e(
                "WALKIE_FLOAT_PTT",
                "addView failed",
                error
            )
        }
    }

    /*
     * ============================================================
     * 隐藏悬浮窗
     * ============================================================
     */

    private fun hideFloatingWindow() {

        val view =
            floatingRoot
                ?: return

        try {

            windowManager.removeView(
                view
            )

        } catch (
            ignored: Exception
        ) {
        }

        floatingRoot =
            null

        floatingButton =
            null

        statusText =
            null

        windowParams =
            null
    }

    /*
     * ============================================================
     * 触摸处理
     * ============================================================
     */

    private fun createTouchListener():
            View.OnTouchListener {

        return View.OnTouchListener {
                view,
                event ->

            val params =
                windowParams
                    ?: return@OnTouchListener true

            when (
                event.actionMasked
            ) {

                MotionEvent.ACTION_DOWN -> {

                    touching =
                        true

                    dragging =
                        false

                    downRawX =
                        event.rawX

                    downRawY =
                        event.rawY

                    startX =
                        params.x

                    startY =
                        params.y

                    /*
                     * 按下马上申请抢麦。
                     */
                    sendSpeakStart()

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val dx =
                        event.rawX -
                                downRawX

                    val dy =
                        event.rawY -
                                downRawY

                    if (
                        !dragging &&
                        (
                                abs(dx) >=
                                        dp(
                                            DRAG_THRESHOLD_DP
                                        ) ||
                                        abs(dy) >=
                                        dp(
                                            DRAG_THRESHOLD_DP
                                        )
                                )
                    ) {

                        dragging =
                            true
                    }

                    if (
                        dragging
                    ) {

                        params.x =
                            startX +
                                    dx.toInt()

                        params.y =
                            startY +
                                    dy.toInt()

                        clampPosition(
                            params
                        )

                        try {

                            windowManager.updateViewLayout(
                                floatingRoot,
                                params
                            )

                        } catch (
                            ignored: Exception
                        ) {
                        }

                        /*
                         * 拖动过程中不要继续保持讲话。
                         *
                         * 这样可以避免：
                         * 一边拖动按钮，
                         * 一边意外持续占用麦克风。
                         */
                        if (
                            touching
                        ) {

                            touching =
                                false

                            sendSpeakStop()
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (
                        touching
                    ) {

                        sendSpeakStop()
                    }

                    touching =
                        false

                    if (
                        dragging
                    ) {

                        snapToEdge(
                            params
                        )

                        savePosition(
                            params
                        )
                    }

                    dragging =
                        false

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    touching =
                        false

                    sendSpeakStop()

                    if (
                        dragging
                    ) {

                        snapToEdge(
                            params
                        )

                        savePosition(
                            params
                        )
                    }

                    dragging =
                        false

                    true
                }

                else -> {

                    true
                }
            }
        }
    }

    /*
     * ============================================================
     * 开始说话
     * ============================================================
     */

    private fun sendSpeakStart() {

        if (
            !Settings.canDrawOverlays(
                this
            )
        ) {

            openOverlaySettings()

            touching =
                false

            return
        }

        if (
            !connected
        ) {

            touching =
                false

            updateUi()

            return
        }

        if (
            talkStatus ==
            WalkieService.TALK_STATUS_BUSY
        ) {

            touching =
                false

            return
        }

        if (
            talkStatus ==
            WalkieService.TALK_STATUS_REQUESTING ||
            talkStatus ==
            WalkieService.TALK_STATUS_ALLOWED
        ) {

            return
        }

        try {

            val intent =
                Intent(
                    this,
                    WalkieService::class.java
                ).apply {

                    action =
                        WalkieService.ACTION_SPEAK_START
                }

            startService(
                intent
            )

        } catch (
            error: Exception
        ) {

            android.util.Log.e(
                "WALKIE_FLOAT_PTT",
                "start speak failed",
                error
            )

            touching =
                false
        }
    }

    /*
     * ============================================================
     * 停止说话
     * ============================================================
     */

    private fun sendSpeakStop() {

        try {

            val intent =
                Intent(
                    this,
                    WalkieService::class.java
                ).apply {

                    action =
                        WalkieService.ACTION_SPEAK_STOP
                }

            startService(
                intent
            )

        } catch (
            error: Exception
        ) {

            android.util.Log.e(
                "WALKIE_FLOAT_PTT",
                "stop speak failed",
                error
            )
        }
    }

    /*
     * ============================================================
     * UI状态
     * ============================================================
     */

    private fun updateUi() {

        val button =
            floatingButton
                ?: return

        val status =
            statusText
                ?: return

        if (
            !connected
        ) {

            button.text =
                "🔇"

            status.text =
                "离线"

            button.background =
                createCircleBackground(
                    Color.rgb(
                        120,
                        125,
                        135
                    )
                )

            return
        }

        when (
            talkStatus
        ) {

            WalkieService.TALK_STATUS_REQUESTING -> {

                button.text =
                    "🎙"

                status.text =
                    "申请"

                /*
                 * 半透明橙色
                 */
                button.background =
                    createCircleBackground(
                        Color.rgb(
                            255,
                            152,
                            45
                        )
                    )
            }

            WalkieService.TALK_STATUS_ALLOWED -> {

                button.text =
                    "🎙"

                status.text =
                    "讲话"

                /*
                 * 半透明绿色
                 */
                button.background =
                    createCircleBackground(
                        Color.rgb(
                            32,
                            190,
                            125
                        )
                    )
            }

            WalkieService.TALK_STATUS_BUSY -> {

                button.text =
                    "×"

                status.text =
                    "忙线"

                /*
                 * 半透明红色
                 */
                button.background =
                    createCircleBackground(
                        Color.rgb(
                            240,
                            75,
                            75
                        )
                    )
            }

            else -> {

                button.text =
                    "🎙"

                status.text =
                    "待机"

                /*
                 * 半透明蓝色
                 */
                button.background =
                    createCircleBackground(
                        Color.rgb(
                            32,
                            126,
                            255
                        )
                    )
            }
        }
    }

    /*
     * ============================================================
     * 圆形背景
     * ============================================================
     */

    private fun createCircleBackground(
        color: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            shape =
                GradientDrawable.OVAL

            /*
             * ====================================================
             * 核心：
             * 给按钮增加透明度
             *
             * 颜色本身仍然使用 RGB，
             * 这里只统一增加 Alpha。
             *
             * 170 / 255 ≈ 67% 不透明
             * 也就是约 33% 透明。
             * ====================================================
             */
            setColor(
                Color.argb(
                    FLOAT_ALPHA,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            )
        }
    }

    /*
     * ============================================================
     * 限制窗口位置
     * ============================================================
     */

    private fun clampPosition(
        params:
        WindowManager.LayoutParams
    ) {

        val metrics =
            resources.displayMetrics

        val width =
            metrics.widthPixels

        val height =
            metrics.heightPixels

        val size =
            dp(
                FLOAT_SIZE_DP
            )

        val maxX =
            (width - size)
                .coerceAtLeast(
                    0
                )

        val maxY =
            (height - size)
                .coerceAtLeast(
                    0
                )

        params.x =
            params.x.coerceIn(
                0,
                maxX
            )

        params.y =
            params.y.coerceIn(
                0,
                maxY
            )
    }

    /*
     * ============================================================
     * 自动贴边
     * ============================================================
     */

    private fun snapToEdge(
        params:
        WindowManager.LayoutParams
    ) {

        val metrics =
            resources.displayMetrics

        val width =
            metrics.widthPixels

        val size =
            dp(
                FLOAT_SIZE_DP
            )

        val margin =
            dp(
                EDGE_MARGIN_DP
            )

        val centerX =
            params.x +
                    size / 2

        val targetX =
            if (
                centerX <
                width / 2
            ) {

                margin

            } else {

                (
                        width -
                                size -
                                margin
                        )
                    .coerceAtLeast(
                        margin
                    )
            }

        params.x =
            targetX

        clampPosition(
            params
        )

        try {

            windowManager.updateViewLayout(
                floatingRoot,
                params
            )

        } catch (
            ignored: Exception
        ) {
        }
    }

    /*
     * ============================================================
     * 保存位置
     * ============================================================
     */

    private fun savePosition(
        params:
        WindowManager.LayoutParams
    ) {

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
            .edit()
            .putInt(
                PREF_X,
                params.x
            )
            .putInt(
                PREF_Y,
                params.y
            )
            .apply()
    }

    /*
     * ============================================================
     * 读取 X
     * ============================================================
     */

    private fun loadX(
        size: Int
    ): Int {

        val metrics =
            resources.displayMetrics

        val width =
            metrics.widthPixels

        val defaultX =
            width -
                    size -
                    dp(
                        EDGE_MARGIN_DP
                    )

        return getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
            .getInt(
                PREF_X,
                defaultX
            )
            .coerceAtLeast(
                0
            )
    }

    /*
     * ============================================================
     * 读取 Y
     * ============================================================
     */

    private fun loadY(
        size: Int
    ): Int {

        val metrics =
            resources.displayMetrics

        val height =
            metrics.heightPixels

        val defaultY =
            DEFAULT_Y_DP()

        return getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )
            .getInt(
                PREF_Y,
                defaultY
            )
            .coerceIn(
                0,
                (
                        height -
                                size
                        )
                    .coerceAtLeast(
                        0
                    )
            )
    }

    /*
     * ============================================================
     * 默认 Y
     * ============================================================
     */

    private fun DEFAULT_Y_DP():
            Int {

        return dp(
            DEFAULT_Y
        )
    }

    /*
     * ============================================================
     * DP
     * ============================================================
     */

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources.displayMetrics.density
                )
            .toInt()
    }
}