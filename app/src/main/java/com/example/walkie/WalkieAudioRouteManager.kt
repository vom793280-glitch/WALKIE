package com.example.walkie

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class WalkieAudioRouteManager(
    private val context: Context,
    private val logger: (String) -> Unit
) {

    fun configureCommunicationAudioOnce() {

        try {

            val audioManager =
                context.getSystemService(
                    Context.AUDIO_SERVICE
                ) as AudioManager

            @Suppress("DEPRECATION")
            audioManager.mode =
                AudioManager.MODE_IN_COMMUNICATION

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val speaker =
                    audioManager
                        .availableCommunicationDevices
                        .firstOrNull {
                            it.type ==
                                    AudioDeviceInfo
                                        .TYPE_BUILTIN_SPEAKER
                        }

                if (
                    speaker != null
                ) {

                    try {

                        val result =
                            audioManager
                                .setCommunicationDevice(
                                    speaker
                                )

                        logger(
                            "AUDIO: 初始化通信扬声器 result=$result"
                        )

                    } catch (
                        e: Exception
                    ) {

                        logger(
                            "AUDIO: 设置通信扬声器失败=${e.message}"
                        )
                    }

                } else {

                    logger(
                        "AUDIO: 未找到通信扬声器"
                    )
                }

            } else {

                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn =
                    true

                logger(
                    "AUDIO: 旧系统扬声器已开启"
                )
            }

        } catch (
            e: Exception
        ) {

            logger(
                "AUDIO: 初始化音频路由异常=${e.message}"
            )
        }
    }

    fun findBuiltInSpeaker():
            AudioDeviceInfo? {

        return try {

            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.M
            ) {

                null

            } else {

                val audioManager =
                    context.getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                audioManager
                    .getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS
                    )
                    .firstOrNull {
                        it.type ==
                                AudioDeviceInfo
                                    .TYPE_BUILTIN_SPEAKER
                    }
            }

        } catch (
            _: Exception
        ) {

            null
        }
    }
}