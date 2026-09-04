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
 * 当前阶段只负责代码拆分，不改变原有业务逻辑。
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
     * V21：
     * 防止旧录音协程尚未完全退出时，
     * 新的PTT又启动第二个AudioRecord。
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
            recordJob?.isActive ==
            true
        ) {
            return
        }

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

        synchronized(
            audioRecordLock
        ) {

            if (
                recordingStarting
            ) {

                logger(
                    "录音启动流程仍在进行，忽略重复请求"
                )

                return
            }

            if (
                recordJob?.isActive ==
                true
            ) {

                return
            }

            recordingStarting =
                false
        }

        /*
         * 原代码中的第二次安全检查保持。
         */
        synchronized(
            audioRecordLock
        ) {

            if (
                recordingStarting
            ) {

                logger(
                    "录音启动仍在进行，忽略重复启动"
                )

                return
            }

            recordingStarting =
                false
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

                        recordingStarting =
                            false

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

                        } catch (e: Exception) {

                            logger(
                                "AudioRecord创建失败=${e.message}"
                            )

                            null
                        }

                    if (
                        recorder == null
                    ) {

                        recordingStarting =
                            false

                        return@launch
                    }

                    if (
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {

                            recorder.release()

                        } catch (_: Exception) {
                        }

                        recorder =
                            try {

                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    sampleRate,
                                    AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    recordBuffer
                                )

                            } catch (_: Exception) {

                                null
                            }
                    }

                    if (
                        recorder == null ||
                        recorder.state !=
                        AudioRecord.STATE_INITIALIZED
                    ) {

                        try {

                            recorder?.release()

                        } catch (_: Exception) {
                        }

                        recorder =
                            null

                        return@launch
                    }

                    synchronized(
                        audioRecordLock
                    ) {

                        audioRecord =
                            recorder
                    }

                    setupAudioEffects(
                        recorder.audioSessionId
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
                                recorder &&
                                recorder.state ==
                                AudioRecord.STATE_INITIALIZED
                            ) {

                                recorder.startRecording()

                                recordingStarted =
                                    recorder.recordingState ==
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
                            "AudioRecord 启动失败，取消本次讲话"
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

                    /*
                     * 原代码这里连续打印两次，
                     * 纯拆分阶段保持行为一致。
                     */
                    logger(
                        "★开始录音★"
                    )

                    while (
                        scope.isActive &&
                        isSpeaking() &&
                        isTalkAllowed() &&
                        isConnected() &&
                        !isShuttingDown()
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
                            !isShuttingDown()
                        ) {

                            val read =
                                try {

                                    synchronized(
                                        audioRecordLock
                                    ) {

                                        recorder.read(
                                            readBuffer,
                                            0,
                                            readBuffer.size,
                                            AudioRecord.READ_BLOCKING
                                        )
                                    }

                                } catch (e: Exception) {

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

                                Thread.sleep(
                                    4L
                                )
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

                            } catch (e: Throwable) {

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

                        } catch (e: Exception) {

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

                    recordingStarting =
                        false

                    setSpeaking(
                        false
                    )

                    val currentRecorder =
                        recorder

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

        recordingStarting =
            false

        recordJob?.cancel()

        recordJob =
            null

        val recorder =
            synchronized(
                audioRecordLock
            ) {

                audioRecord
            }

        if (
            recorder == null
        ) {

            releaseAudioEffects()

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

        } catch (_: Exception) {
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

        } catch (_: Exception) {
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

        } catch (_: Exception) {
        }
    }

    private fun releaseAudioEffects() {

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        try {
            automaticGainControl?.release()
        } catch (_: Exception) {
        }

        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {
        }

        noiseSuppressor =
            null

        automaticGainControl =
            null

        acousticEchoCanceler =
            null
    }
}