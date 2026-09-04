package com.example.walkie

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WALKIE GPS 距离 UI 数据仓库
 *
 * 作用：
 *
 * WalkieLocationManager 负责：
 *   1. 接收成员 GPS
 *   2. 计算距离
 *
 * WalkieLocationUiStore 负责：
 *   1. 保存当前距离
 *   2. 通知 Compose UI 刷新
 *
 * 不参与：
 *   1. UDP
 *   2. 音频
 *   3. 抢麦
 *   4. 重连
 */
object WalkieLocationUiStore {

    /**
     * key：
     *   UserID
     *
     * value：
     *   距离，单位米
     */
    private val _memberDistancesMeters =
        MutableStateFlow(
            emptyMap<String, Double>()
        )

    /**
     * UI 只读距离数据。
     */
    val memberDistancesMeters:
            StateFlow<Map<String, Double>> =
        _memberDistancesMeters.asStateFlow()

    /**
     * 更新全部成员距离。
     */
    fun updateDistances(
        distances: Map<String, Double>
    ) {

        _memberDistancesMeters.value =
            LinkedHashMap(
                distances
            )
    }

    /**
     * 删除指定成员。
     */
    fun removeUser(
        userId: String
    ) {

        val cleanUserId =
            userId.trim()

        if (
            cleanUserId.isBlank()
        ) {
            return
        }

        val current =
            _memberDistancesMeters.value

        if (
            !current.containsKey(
                cleanUserId
            )
        ) {
            return
        }

        val updated =
            LinkedHashMap(
                current
            )

        updated.remove(
            cleanUserId
        )

        _memberDistancesMeters.value =
            updated
    }

    /**
     * 清空全部距离。
     */
    fun clear() {

        _memberDistancesMeters.value =
            emptyMap()
    }
}