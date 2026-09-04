package com.example.walkie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * WALKIE AudioRecord录音模块。
 *
 * V24.9.1：
 * 只负责录音、PCM采集、Opus编码和音频发送。
 *
 * 本版闪退修复重点：
 * 1. 防止快速连续PTT同时启动多个AudioRecord。
 * 2. stopRecording()取消旧任务后，不提前把启动状态恢复为false。
 * 3. 只有旧录音协程真正进入finally并完成资源释放后，
 *    才允许新的AudioRecord启动。
 */
class WalkieRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val audioPacketSize: Int,
    private val maxOpusPacketSize: Int,
    private val opusEncoderProvider: () -> OpusEncoder?,
    private val buildAudioPacket: (ByteArray) -> ByteArray,
    private val sendAudioPacket: (ByteArray) -> Unit,
    private val recordAudioTransmit: (Int) -> Unit,
    private val isTalkAllowed: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val isSpeaking: () -> Boolean,
    private val isShuttingDown: () -> Boolean,
    private val setSpeaking: (Boolean) -> Unit,
    private val setTalkAllowed: (Boolean) -> Unit,
    private val setTalkRequesting: (Boolean) -> Unit,
    private val setTalkStatus: (String) -> Unit,
    private val talkStatusReleased: String,
    private val sendTalkStop: () -> Unit,
    private val logger: (String) -> Unit
) {

    private var audioRecord: AudioRecord? =
        null

    private var recordJob: Job? =
        null

    private val audioRecordLock =
        Any()

    /*
     * V24.9.1：
     *
     * true：
     *   当前正在启动、录音或者正在退出旧录音。
     *
     * false：
     *   当前确认没有旧录音流程占用AudioRecord。
     *
     * 只有finally真正完成资源释放后，
     * 才恢复为false。
     */
    private var recordingStarting =
        false

    private var noiseSuppressor:
            NoiseSuppressor? =
        null

    private var automaticGainControl:
            AutomaticGainControl? =
        null

    private var acousticEchoCanceler:
            AcousticEchoCanceler? =
        null

    fun startRecording() {

        if (
            !isTalkAllowed()
        ) {
            return
        }

        if (
            context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            logger(
                "没有录音权限"
            )

            return
        }

        /*
         * 所有启动条件放在同一个锁里。
         *
         * 关键：
         * recordingStarting一旦变成true，
         * 在旧录音流程真正结束前绝不允许第二次启动。
         */
        synchronized(
            audioRecordLock
        ) {

            if (
                recordingStarting
            ) {

                logger(
                    "录音生命周期仍在进行，忽略重复启动"
                )

                return
            }

            if (
                recordJob?.isActive ==
                true
            ) {

                logger(
                    "旧录音任务仍在运行，忽略重复启动"
                )

                return
            }

            recordingStarting =
                true
        }

        recordJob =
            scope.launch {

                var recorder:
                        AudioRecord? =
                    null

                try {

                    val minBuffer =
                        AudioRecord.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )

                    if (
                        minBuffer <= 0
                    ) {

                        logger(
                            "AudioRecord最小缓冲区无效=$minBuffer"
                        )

                        return@launch
                    }

                    val recordBuffer =
                        maxOf(
                            minBuffer * 2,
                            audioPacketSize * 4,
                            4096
                        )

                    recorder =
                        try {

                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                recordBuffer
                            )

                        } catch (
                            e: Throwable
                        ) {

                            logger(
                                "AudioRecord创建失败=${e.message}"
                            )

                            null
                        }

                    if (
                        recorder == null
                    ) {

                        return@launch
                    }

                    if (
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        logger(
                            "AudioRecord第一次初始化失败，尝试重新创建"
                        )

                        try {

                            recorder.release()

                        } catch (_: Throwable) {
                        }

                        recorder =
                            null

                        recorder =
                            try {

                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    sampleRate,
                                    AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    recordBuffer
                                )

                            } catch (
                                e: Throwable
                            ) {

                                logger(
                                    "AudioRecord第二次创建失败=${e.message}"
                                )

                                null
                            }
                    }

                    val validRecorder =
                        recorder

                    if (
                        validRecorder == null ||
                        validRecorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {

                            validRecorder?.release()

                        } catch (_: Throwable) {
                        }

                        recorder =
                            null

                        logger(
                            "AudioRecord最终初始化失败"
                        )

                        return@launch
                    }

                    synchronized(
                        audioRecordLock
                    ) {

                        /*
                         * stopRecording()可能已经在启动过程中被调用。
                         *
                         * 如果此时启动流程已经被停止，
                         * 不再把这个新创建的AudioRecord交给全局对象。
                         */
                        if (
                            !recordingStarting ||
                            recordJob?.isCancelled ==
                            true ||
                            isShuttingDown()
                        ) {

                            try {

                                validRecorder.release()

                            } catch (_: Throwable) {
                            }

                            recorder =
                                null

                            logger(
                                "录音启动过程中收到停止请求，取消本次AudioRecord"
                            )

                            return@launch
                        }

                        audioRecord =
                            validRecorder
                    }

                    setupAudioEffects(
                        validRecorder.audioSessionId
                    )

                    val packetBuffer =
                        ByteArray(
                            audioPacketSize
                        )

                    val readBuffer =
                        ByteArray(
                            audioPacketSize
                        )

                    setSpeaking(
                        true
                    )

                    var recordingStarted =
                        false

                    try {

                        synchronized(
                            audioRecordLock
                        ) {

                            if (
                                audioRecord ===
                                validRecorder &&
                                recordingStarting &&
                                !isShuttingDown() &&
                                validRecorder.state ==
                                AudioRecord.STATE_INITIALIZED
                            ) {

                                validRecorder.startRecording()

                                recordingStarted =
                                    validRecorder.recordingState ==
                                            AudioRecord.RECORDSTATE_RECORDING
                            }
                        }

                    } catch (
                        e: Throwable
                    ) {

                        logger(
                            "AudioRecord.startRecording异常=${e.message}"
                        )

                        recordingStarted =
                            false
                    }

                    if (
                        !recordingStarted
                    ) {

                        setSpeaking(
                            false
                        )

                        setTalkAllowed(
                            false
                        )

                        setTalkRequesting(
                            false
                        )

                        logger(
                            "AudioRecord启动失败，取消本次讲话"
                        )

                        setTalkStatus(
                            talkStatusReleased
                        )

                        sendTalkStop()

                        return@launch
                    }

                    logger(
                        "★开始录音★"
                    )

                    while (
                        scope.isActive &&
                        isSpeaking() &&
                        isTalkAllowed() &&
                        isConnected() &&
                        !isShuttingDown() &&
                        recordingStarting
                    ) {

                        var filled =
                            0

                        while (
                            filled <
                            audioPacketSize &&
                            scope.isActive &&
                            isSpeaking() &&
                            isTalkAllowed() &&
                            isConnected() &&
                            !isShuttingDown() &&
                            recordingStarting
                        ) {

                            val read =
                                try {

                                    synchronized(
                                        audioRecordLock
                                    ) {

                                        if (
                                            audioRecord !==
                                            validRecorder
                                        ) {

                                            -1

                                        } else {

                                            validRecorder.read(
                                                readBuffer,
                                                0,
                                                readBuffer.size,
                                                AudioRecord.READ_BLOCKING
                                            )
                                        }
                                    }

                                } catch (
                                    e: Throwable
                                ) {

                                    logger(
                                        "AudioRecord.read异常=${e.message}"
                                    )

                                    -1
                                }

                            if (
                                read > 0
                            ) {

                                val copySize =
                                    min(
                                        read,
                                        audioPacketSize -
                                                filled
                                    )

                                System.arraycopy(
                                    readBuffer,
                                    0,
                                    packetBuffer,
                                    filled,
                                    copySize
                                )

                                filled +=
                                    copySize

                            } else if (
                                read < 0
                            ) {

                                logger(
                                    "AudioRecord.read返回=$read，结束录音"
                                )

                                break

                            } else {

                                try {

                                    Thread.sleep(
                                        4L
                                    )

                                } catch (
                                    e: InterruptedException
                                ) {

                                    Thread.currentThread()
                                        .interrupt()

                                    break
                                }
                            }
                        }

                        if (
                            filled !=
                            audioPacketSize
                        ) {

                            continue
                        }

                        val pcm =
                            ShortArray(
                                audioPacketSize / 2
                            )

                        var index =
                            0

                        while (
                            index <
                            pcm.size
                        ) {

                            val low =
                                packetBuffer[
                                    index * 2
                                ].toInt() and
                                        0xff

                            val high =
                                packetBuffer[
                                    index * 2 + 1
                                ].toInt()

                            pcm[index] =
                                (
                                        (high shl 8) or
                                                low
                                        ).toShort()

                            index++
                        }

                        val encoder =
                            opusEncoderProvider()
                                ?: continue

                        val opus =
                            try {

                                encoder.encode(
                                    pcm
                                )

                            } catch (
                                e: Throwable
                            ) {

                                logger(
                                    "Opus编码异常=${e.message}"
                                )

                                null
                            }

                        if (
                            opus == null ||
                            opus.isEmpty()
                        ) {

                            continue
                        }

                        if (
                            opus.size >
                            maxOpusPacketSize
                        ) {

                            logger(
                                "Opus包过大=${opus.size}"
                            )

                            continue
                        }

                        try {

                            val framedAudio =
                                buildAudioPacket(
                                    opus
                                )

                            sendAudioPacket(
                                framedAudio
                            )

                            recordAudioTransmit(
                                opus.size
                            )

                        } catch (
                            e: Throwable
                        ) {

                            logger(
                                "OPUS发送失败=${e.message}"
                            )

                            break
                        }
                    }

                } catch (
                    e: Throwable
                ) {

                    logger(
                        "录音线程异常=${e.message}"
                    )

                } finally {

                    setSpeaking(
                        false
                    )

                    val currentRecorder =
                        recorder

                    /*
                     * 先停止AudioRecord，再解除全局引用，
                     * 最后释放AudioRecord对象。
                     */
                    if (
                        currentRecorder !=
                        null
                    ) {

                        synchronized(
                            audioRecordLock
                        ) {

                            if (
                                audioRecord ===
                                currentRecorder
                            ) {

                                try {

                                    if (
                                        currentRecorder.recordingState ==
                                        AudioRecord.RECORDSTATE_RECORDING
                                    ) {

                                        currentRecorder.stop()
                                    }

                                } catch (
                                    e: Throwable
                                ) {

                                    logger(
                                        "finally AudioRecord.stop异常=" +
                                                e.message
                                    )
                                }

                                audioRecord =
                                    null

                                try {

                                    currentRecorder.release()

                                } catch (
                                    e: Throwable
                                ) {

                                    logger(
                                        "finally AudioRecord.release异常=" +
                                                e.message
                                    )
                                }
                            }
                        }
                    }

                    releaseAudioEffects()

                    /*
                     * 极其重要：
                     *
                     * 只有旧AudioRecord已经完成release，
                     * 才解除recordingStarting。
                     *
                     * 这样下一次PTT不会和旧录音生命周期重叠。
                     */
                    synchronized(
                        audioRecordLock
                    ) {

                        if (
                            recordJob?.isActive !=
                            true
                        ) {
                            recordingStarting =
                                false
                        }
                    }

                    logger(
                        "录音结束"
                    )
                }
            }
    }

    fun stopRecording() {

        setSpeaking(
            false
        )

        /*
         * 这里故意不再设置：
         *
         * recordingStarting = false
         *
         * 因为旧录音协程可能还没有真正退出。
         *
         * 必须等finally完成AudioRecord释放后，
         * 才允许新的PTT启动。
         */
        val recorder =
            synchronized(
                audioRecordLock
            ) {

                recordJob?.cancel()

                audioRecord
            }

        if (
            recorder == null
        ) {

            /*
             * 如果此时AudioRecord尚未创建，
             * 仍然保持recordingStarting=true，
             * 等启动协程进入finally后统一解除。
             */
            return
        }

        synchronized(
            audioRecordLock
        ) {

            if (
                audioRecord !==
                recorder
            ) {

                return@synchronized
            }

            try {

                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {

                    recorder.stop()
                }

            } catch (
                e: Throwable
            ) {

                logger(
                    "AudioRecord.stop异常=${e.message}"
                )
            }

            /*
             * 立即释放旧AudioRecord，
             * 让阻塞中的read尽快退出。
             */
            try {

                recorder.release()

            } catch (
                e: Throwable
            ) {

                logger(
                    "AudioRecord.release异常=${e.message}"
                )
            }

            if (
                audioRecord ===
                recorder
            ) {

                audioRecord =
                    null
            }
        }

        releaseAudioEffects()
    }

    private fun setupAudioEffects(
        audioSessionId: Int
    ) {

        releaseAudioEffects()

        try {

            if (
                NoiseSuppressor.isAvailable()
            ) {

                noiseSuppressor =
                    NoiseSuppressor.create(
                        audioSessionId
                    )

                noiseSuppressor?.enabled =
                    true
            }

        } catch (
            _ : Throwable
        ) {
        }

        try {

            if (
                AutomaticGainControl.isAvailable()
            ) {

                automaticGainControl =
                    AutomaticGainControl.create(
                        audioSessionId
                    )

                automaticGainControl?.enabled =
                    true
            }

        } catch (
            _ : Throwable
        ) {
        }

        try {

            if (
                AcousticEchoCanceler.isAvailable()
            ) {

                acousticEchoCanceler =
                    AcousticEchoCanceler.create(
                        audioSessionId
                    )

                acousticEchoCanceler?.enabled =
                    true
            }

        } catch (
            _ : Throwable
        ) {
        }
    }

    private fun releaseAudioEffects() {

        try {
            noiseSuppressor?.release()
        } catch (
            _ : Throwable
        ) {
        }

        try {
            automaticGainControl?.release()
        } catch (
            _ : Throwable
        ) {
        }

        try {
            acousticEchoCanceler?.release()
        } catch (
            _ : Throwable
        ) {
        }

        noiseSuppressor =
            null

        automaticGainControl =
            null

        acousticEchoCanceler =
            null
    }
}