package com.example.walkie

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WalkieTalkManager(
    private val scope: CoroutineScope,

    private val isConnected: () -> Boolean,
    private val isChannelSwitching: () -> Boolean,
    private val isShuttingDown: () -> Boolean,

    private val isTalkRequesting: () -> Boolean,
    private val isTalkAllowed: () -> Boolean,
    private val isSpeaking: () -> Boolean,

    private val setTalkRequesting: (Boolean) -> Unit,
    private val setTalkAllowed: (Boolean) -> Unit,
    private val setSpeaking: (Boolean) -> Unit,

    private val setTalkStatus: (String) -> Unit,

    private val talkStatusRequesting: String,
    private val talkStatusAllowed: String,
    private val talkStatusBusy: String,
    private val talkStatusReleased: String,

    private val sendTalkStart: () -> Unit,
    private val sendTalkStop: () -> Unit,

    private val startRecording: () -> Unit,
    private val stopRecording: () -> Unit,

    private val playTalkGrantedTone: () -> Unit,

    private val isConnectionGenerationCurrent: (Long) -> Boolean,

    private val logger: (String) -> Unit
) {

    /*
     * ============================================================
     * 请求抢麦
     *
     * 原 WalkieService.requestTalk() 完整搬迁。
     *
     * 不修改原逻辑：
     *
     * 按住
     *   ↓
     * REQUESTING
     *   ↓
     * WALKIE_TALK_START
     *   ↓
     * 等待 TALK_OK / TALK_BUSY
     *   ↓
     * 3秒没有响应自动 RELEASED
     * ============================================================
     */
    fun requestTalk() {

        if (
            !isConnected() ||
            isChannelSwitching()
        ) {

            return
        }

        if (
            isTalkRequesting() ||
            isTalkAllowed()
        ) {

            return
        }

        /*
         * 标记正在抢麦。
         */
        setTalkRequesting(
            true
        )

        setTalkAllowed(
            false
        )

        setSpeaking(
            false
        )

        setTalkStatus(
            talkStatusRequesting
        )

        /*
         * 立即发送抢麦请求。
         */
        sendTalkStart()

        /*
         * ========================================================
         * 原 V21 抢麦3秒超时保护
         * ========================================================
         */
        scope.launch {

            delay(
                3000L
            )

            if (
                isTalkRequesting() &&
                !isTalkAllowed() &&
                isConnected() &&
                !isShuttingDown()
            ) {

                logger(
                    "PTT 抢麦 3 秒无响应，自动超时"
                )

                setTalkRequesting(
                    false
                )

                setTalkAllowed(
                    false
                )

                setSpeaking(
                    false
                )

                setTalkStatus(
                    talkStatusReleased
                )

                /*
                 * 告诉服务器：
                 * 本次没有继续等待抢麦。
                 */
                sendTalkStop()
            }
        }
    }

    /*
     * ============================================================
     * 释放抢麦
     *
     * 原 WalkieService.releaseTalk() 完整搬迁。
     * ============================================================
     */
    fun releaseTalk() {

        setSpeaking(
            false
        )

        setTalkRequesting(
            false
        )

        setTalkAllowed(
            false
        )

        stopRecording()

        if (
            isConnected()
        ) {

            sendTalkStop()
        }

        setTalkStatus(
            talkStatusReleased
        )
    }

    /*
     * ============================================================
     * 处理服务器返回的 TALK 控制消息
     *
     * 返回 true：
     *   当前消息已经被 TalkManager 消费
     *
     * 返回 false：
     *   不是 TALK 消息，交给 Service 后面的逻辑处理。
     * ============================================================
     */
    fun handleIncomingMessage(
        text: String,
        generation: Long
    ): Boolean {

        /*
         * ========================================================
         * TALK_OK
         * ========================================================
         */
        if (
            text ==
            "WALKIE_TALK_OK"
        ) {

            if (
                isTalkRequesting() &&
                !isSpeaking() &&
                isConnected() &&
                !isShuttingDown() &&
                isConnectionGenerationCurrent(
                    generation
                )
            ) {

                /*
                 * ====================================================
                 * 原 V21 逻辑：
                 *
                 * 1. 结束 REQUESTING
                 * 2. 设置 ALLOWED
                 * 3. 更新界面
                 * 4. 播放提示音
                 * 5. 开始录音
                 * ====================================================
                 */

                setTalkRequesting(
                    false
                )

                setTalkAllowed(
                    true
                )

                setTalkStatus(
                    talkStatusAllowed
                )

                /*
                 * 抢麦成功提示音。
                 *
                 * 必须在开始录音之前播放。
                 */
                playTalkGrantedTone()

                startRecording()
            }

            return true
        }

        /*
         * ========================================================
         * TALK_BUSY
         * ========================================================
         */
        if (
            text ==
            "WALKIE_TALK_BUSY"
        ) {

            setTalkRequesting(
                false
            )

            setTalkAllowed(
                false
            )

            setSpeaking(
                false
            )

            stopRecording()

            setTalkStatus(
                talkStatusBusy
            )

            return true
        }

        /*
         * ========================================================
         * TALK_RELEASED
         * ========================================================
         */
        if (
            text ==
            "WALKIE_TALK_RELEASED"
        ) {

            setTalkRequesting(
                false
            )

            setTalkAllowed(
                false
            )

            setSpeaking(
                false
            )

            stopRecording()

            setTalkStatus(
                talkStatusReleased
            )

            return true
        }

        return false
    }

    /*
     * ============================================================
     * 频道切换 / 频道删除 / 网络断开等场景使用
     *
     * 这里只负责把本地抢麦状态恢复到释放状态。
     *
     * 注意：
     * 不主动发送 TALK_STOP。
     * 是否发送由原来的业务流程决定。
     * ============================================================
     */
    fun resetLocalTalkState(
        updateUi: Boolean = true
    ) {

        setTalkRequesting(
            false
        )

        setTalkAllowed(
            false
        )

        setSpeaking(
            false
        )

        stopRecording()

        if (
            updateUi
        ) {

            setTalkStatus(
                talkStatusReleased
            )
        }
    }
}