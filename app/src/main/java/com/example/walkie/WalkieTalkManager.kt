package com.example.walkie

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    companion object {

        /*
         * ========================================================
         * BUSY 自动恢复时间
         *
         * UDP 是不可靠传输。
         *
         * 如果：
         *
         *   TALK_BUSY 到达
         *   ↓
         *   后续 TALK_RELEASED 丢包
         *
         * 原版本会永久停在 BUSY。
         *
         * 现在：
         *
         *   BUSY
         *   ↓
         *   最多等待 2 秒
         *   ↓
         *   自动恢复 RELEASED
         *
         * 不改变服务器实际麦权。
         * 只是防止手机 UI 因为丢一个 UDP 包永久锁死。
         * ========================================================
         */
        private const val BUSY_AUTO_RESET_DELAY = 2000L
    }

    /*
     * BUSY 自动恢复任务。
     *
     * 必须保存 Job，
     * 防止多个 BUSY 定时器同时存在。
     */
    private var busyResetJob: Job? = null

    /*
     * ============================================================
     * 取消 BUSY 自动恢复任务
     * ============================================================
     */
    private fun cancelBusyResetJob() {

        busyResetJob?.cancel()

        busyResetJob =
            null
    }

    /*
     * ============================================================
     * 请求抢麦
     *
     * 原 WalkieService.requestTalk() 完整搬迁。
     *
     * 按住
     *   ↓
     * REQUESTING
     *   ↓
     * WALKIE_TALK_START
     *   ↓
     * 等待 TALK_OK / TALK_BUSY
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
         * 新的一次抢麦开始。
         *
         * 如果之前存在 BUSY 定时器，
         * 先取消。
         */
        cancelBusyResetJob()

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
         * 3秒抢麦响应超时保护
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

        /*
         * 用户主动释放时，
         * 之前的 BUSY 自动恢复任务已经没有意义。
         */
        cancelBusyResetJob()

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
     *   当前消息已经被 TalkManager 消费。
     *
     * 返回 false：
     *   不是 TALK 消息。
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

            /*
             * 收到成功消息后，
             * 取消之前可能存在的 BUSY 自动恢复任务。
             */
            cancelBusyResetJob()

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

            /*
             * 先取消之前可能存在的 BUSY 任务。
             */
            cancelBusyResetJob()

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

            /*
             * ====================================================
             * ★★★ 关键修复 ★★★
             *
             * BUSY 不能永久存在。
             *
             * UDP 的 TALK_RELEASED 可能丢包。
             *
             * 这里增加本地保护：
             *
             * BUSY
             *   ↓
             * 等待2秒
             *   ↓
             * 自动恢复 RELEASED
             *
             * 这只恢复“本地 UI / 本地抢麦状态”，
             * 不会强行改变服务器当前真正的麦权。
             *
             * 如果服务器此时仍有人讲话，
             * 用户再次按住时服务器依然会返回 BUSY。
             * ====================================================
             */
            busyResetJob =
                scope.launch {

                    delay(
                        BUSY_AUTO_RESET_DELAY
                    )

                    /*
                     * 只有当前仍然是 BUSY，
                     * 才允许这个任务执行恢复。
                     *
                     * 避免：
                     *
                     * BUSY
                     * ↓
                     * 用户新抢麦
                     * ↓
                     * 旧任务又把新状态清掉
                     */
                    if (
                        isConnected() &&
                        !isShuttingDown() &&
                        !isTalkRequesting() &&
                        !isTalkAllowed() &&
                        !isSpeaking()
                    ) {

                        logger(
                            "PTT BUSY 超时，自动恢复本地抢麦状态"
                        )

                        setTalkStatus(
                            talkStatusReleased
                        )
                    }

                    busyResetJob =
                        null
                }

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

            /*
             * 服务器明确告诉我们：
             * 麦权已经释放。
             */
            cancelBusyResetJob()

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

        /*
         * 状态被外部重置时，
         * 取消 BUSY 自动恢复任务。
         */
        cancelBusyResetJob()

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