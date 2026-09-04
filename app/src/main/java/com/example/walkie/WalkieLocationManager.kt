package com.example.walkie

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WALKIE 成员位置管理器
 *
 * VPS -> Android：
 *
 * WALKIE_MEMBER_LOCATION:
 * channel:userId:username:latitude:longitude:timestamp
 *
 * 例如：
 *
 * WALKIE_MEMBER_LOCATION:public:U-D-12345678:张三:29.563010:106.551560:1788499200
 *
 * 当前阶段：
 *
 * 1. 独立保存成员 GPS
 * 2. 解析 VPS 位置消息
 * 3. 计算成员之间直线距离
 * 4. 将距离同步给 Android UI
 *
 * 暂时不改现有：
 *
 * 1. WalkieChannelMemberManager
 * 2. WalkieService 网络协议
 * 3. 音频
 * 4. 抢麦
 */
class WalkieLocationManager(
    private val getCurrentChannel: () -> String,
    private val getMyUserId: () -> String,
    private val getMyLatitude: () -> Double?,
    private val getMyLongitude: () -> Double?,
    private val logger: (String) -> Unit
) {

    companion object {

        const val MSG_MEMBER_LOCATION =
            "WALKIE_MEMBER_LOCATION"

        private const val LOCATION_STALE_MS =
            90_000L

        private const val EARTH_RADIUS_METERS =
            6_371_000.0
    }

    /**
     * 一个成员的位置。
     */
    data class MemberLocation(
        val userId: String,
        val username: String,
        val channelName: String,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val receivedAtMs: Long
    )

    /**
     * 当前已收到的位置。
     *
     * key = UserID
     */
    private val locations:
            MutableMap<String, MemberLocation> =
        LinkedHashMap()

    /**
     * 处理 VPS 发来的位置消息。
     */
    @Synchronized
    fun handle(
        text: String
    ) {

        if (
            !text.startsWith(
                "$MSG_MEMBER_LOCATION:"
            )
        ) {
            return
        }

        val payload =
            text.substringAfter(
                "$MSG_MEMBER_LOCATION:",
                ""
            )

        if (
            payload.isBlank()
        ) {
            return
        }

        /*
         * 格式：
         *
         * channel
         * userId
         * username
         * latitude
         * longitude
         * timestamp
         */
        val parts =
            payload.split(
                ":",
                limit = 6
            )

        if (
            parts.size < 6
        ) {

            logger(
                "GPS成员位置格式错误: $text"
            )

            return
        }

        val channelName =
            parts[0]
                .trim()

        val userId =
            parts[1]
                .trim()

        val username =
            parts[2]
                .trim()
                .ifBlank {
                    "未命名用户"
                }

        val latitude =
            parts[3]
                .trim()
                .toDoubleOrNull()

        val longitude =
            parts[4]
                .trim()
                .toDoubleOrNull()

        val timestamp =
            parts[5]
                .trim()
                .toLongOrNull()
                ?: 0L

        if (
            channelName.isBlank() ||
            userId.isBlank() ||
            latitude == null ||
            longitude == null
        ) {

            logger(
                "GPS成员位置参数无效: $text"
            )

            return
        }

        if (
            latitude < -90.0 ||
            latitude > 90.0
        ) {

            logger(
                "GPS纬度超出范围: $latitude"
            )

            return
        }

        if (
            longitude < -180.0 ||
            longitude > 180.0
        ) {

            logger(
                "GPS经度超出范围: $longitude"
            )

            return
        }

        val currentChannel =
            getCurrentChannel()
                .trim()

        /*
         * 只保存当前频道成员。
         */
        if (
            currentChannel.isBlank() ||
            channelName != currentChannel
        ) {
            return
        }

        val nowMs =
            System.currentTimeMillis()

        val normalizedTimestamp =
            normalizeTimestamp(
                timestamp
            )

        /*
         * 防止明显错误的未来时间。
         */
        if (
            normalizedTimestamp > 0L
        ) {

            val nowSeconds =
                nowMs / 1000L

            if (
                normalizedTimestamp >
                nowSeconds + 300L
            ) {

                logger(
                    "GPS时间戳异常，忽略: " +
                            "user=$userId " +
                            "timestamp=$normalizedTimestamp"
                )

                return
            }
        }

        val location =
            MemberLocation(
                userId =
                    userId,

                username =
                    cleanUsername(
                        username
                    ),

                channelName =
                    channelName,

                latitude =
                    latitude,

                longitude =
                    longitude,

                timestamp =
                    normalizedTimestamp,

                receivedAtMs =
                    nowMs
            )

        locations[userId] =
            location

        logger(
            "GPS成员位置收到: " +
                    "user=${location.username} " +
                    "userId=${location.userId} " +
                    "lat=${formatCoordinate(location.latitude)} " +
                    "lon=${formatCoordinate(location.longitude)}"
        )

        val myUserId =
            getMyUserId()
                .trim()

        if (
            myUserId.isNotBlank() &&
            userId == myUserId
        ) {

            logger(
                "GPS收到自己的位置: user=$userId"
            )
        }

        /*
         * ============================================================
         * 更新 UI 距离
         * ============================================================
         *
         * GPS 到达 Android 后，
         * 距离直接在本机计算。
         *
         * 不需要再次请求 VPS。
         */
        publishDistancesLocked()
    }

    /**
     * 当前频道所有未过期位置。
     */
    @Synchronized
    fun getActiveLocations():
            List<MemberLocation> {

        removeStaleLocked()

        val currentChannel =
            getCurrentChannel()
                .trim()

        return locations.values
            .filter {
                it.channelName ==
                        currentChannel
            }
            .sortedBy {
                it.username
            }
    }

    /**
     * 获取指定成员位置。
     */
    @Synchronized
    fun getLocation(
        userId: String
    ): MemberLocation? {

        removeStaleLocked()

        return locations[
            userId.trim()
        ]
    }

    /**
     * 删除指定成员。
     */
    @Synchronized
    fun remove(
        userId: String
    ) {

        val cleanUserId =
            userId.trim()

        locations.remove(
            cleanUserId
        )

        WalkieLocationUiStore
            .removeUser(
                cleanUserId
            )
    }

    /**
     * 清空全部位置。
     */
    @Synchronized
    fun clear() {

        locations.clear()

        WalkieLocationUiStore
            .clear()
    }

    /**
     * 计算自己到指定成员的距离。
     *
     * 返回单位：
     * 米
     */
    @Synchronized
    fun distanceToMemberMeters(
        userId: String
    ): Double? {

        val myLatitude =
            getMyLatitude()
                ?: return null

        val myLongitude =
            getMyLongitude()
                ?: return null

        val member =
            getLocation(
                userId
            )
                ?: return null

        return calculateDistanceMeters(
            latitude1 =
                myLatitude,

            longitude1 =
                myLongitude,

            latitude2 =
                member.latitude,

            longitude2 =
                member.longitude
        )
    }

    /**
     * 获取全部成员距离。
     */
    @Synchronized
    fun getMemberDistancesMeters():
            Map<String, Double> {

        val myLatitude =
            getMyLatitude()
                ?: return emptyMap()

        val myLongitude =
            getMyLongitude()
                ?: return emptyMap()

        removeStaleLocked()

        val currentChannel =
            getCurrentChannel()
                .trim()

        val result =
            LinkedHashMap<String, Double>()

        for (
        location in
        locations.values
        ) {

            if (
                location.channelName !=
                currentChannel
            ) {
                continue
            }

            result[
                location.userId
            ] =
                calculateDistanceMeters(
                    latitude1 =
                        myLatitude,

                    longitude1 =
                        myLongitude,

                    latitude2 =
                        location.latitude,

                    longitude2 =
                        location.longitude
                )
        }

        return result
    }

    /**
     * 将最新距离同步到 UI。
     */
    @Synchronized
    private fun publishDistancesLocked() {

        val distances =
            getMemberDistancesMeters()

        WalkieLocationUiStore
            .updateDistances(
                distances
            )
    }

    /**
     * 将距离格式化成：
     *
     * 326 m
     * 1.24 km
     */
    fun formatDistance(
        distanceMeters: Double
    ): String {

        if (
            distanceMeters < 1000.0
        ) {

            return String.format(
                java.util.Locale.US,
                "%.0f m",
                distanceMeters
            )
        }

        return String.format(
            java.util.Locale.US,
            "%.2f km",
            distanceMeters / 1000.0
        )
    }

    /**
     * 直接获取指定成员的显示距离。
     */
    @Synchronized
    fun getFormattedDistance(
        userId: String
    ): String? {

        val distance =
            distanceToMemberMeters(
                userId
            )
                ?: return null

        return formatDistance(
            distance
        )
    }

    /**
     * 清理过期位置。
     */
    @Synchronized
    private fun removeStaleLocked() {

        val now =
            System.currentTimeMillis()

        val iterator =
            locations
                .entries
                .iterator()

        var changed =
            false

        while (
            iterator.hasNext()
        ) {

            val entry =
                iterator.next()

            val location =
                entry.value

            if (
                now -
                location.receivedAtMs >
                LOCATION_STALE_MS
            ) {

                logger(
                    "GPS成员位置过期: " +
                            "user=${location.username} " +
                            "userId=${location.userId}"
                )

                iterator.remove()

                changed =
                    true
            }
        }

        if (
            changed
        ) {

            /*
             * 有成员位置过期，
             * 同步刷新 UI 距离。
             */
            val myLatitude =
                getMyLatitude()

            val myLongitude =
                getMyLongitude()

            if (
                myLatitude != null &&
                myLongitude != null
            ) {

                val currentChannel =
                    getCurrentChannel()
                        .trim()

                val distances =
                    LinkedHashMap<String, Double>()

                for (
                location in
                locations.values
                ) {

                    if (
                        location.channelName !=
                        currentChannel
                    ) {
                        continue
                    }

                    distances[
                        location.userId
                    ] =
                        calculateDistanceMeters(
                            latitude1 =
                                myLatitude,

                            longitude1 =
                                myLongitude,

                            latitude2 =
                                location.latitude,

                            longitude2 =
                                location.longitude
                        )
                }

                WalkieLocationUiStore
                    .updateDistances(
                        distances
                    )

            } else {

                WalkieLocationUiStore
                    .clear()
            }
        }
    }

    /**
     * Unix 秒 / Unix 毫秒兼容。
     */
    private fun normalizeTimestamp(
        timestamp: Long
    ): Long {

        if (
            timestamp <= 0L
        ) {
            return 0L
        }

        if (
            timestamp >
            100_000_000_000L
        ) {

            return timestamp / 1000L
        }

        return timestamp
    }

    /**
     * Haversine 球面距离。
     *
     * 这里计算的是地表两点之间的直线距离，
     * 不是道路距离。
     */
    private fun calculateDistanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {

        val lat1 =
            Math.toRadians(
                latitude1
            )

        val lat2 =
            Math.toRadians(
                latitude2
            )

        val deltaLat =
            Math.toRadians(
                latitude2 -
                        latitude1
            )

        val deltaLon =
            Math.toRadians(
                longitude2 -
                        longitude1
            )

        val sinLat =
            sin(
                deltaLat / 2.0
            )

        val sinLon =
            sin(
                deltaLon / 2.0
            )

        val a =
            sinLat * sinLat +
                    cos(lat1) *
                    cos(lat2) *
                    sinLon * sinLon

        val safeA =
            a.coerceIn(
                0.0,
                1.0
            )

        val c =
            2.0 *
                    asin(
                        sqrt(
                            safeA
                        )
                    )

        return EARTH_RADIUS_METERS *
                c
    }

    /**
     * 用户名清理。
     */
    private fun cleanUsername(
        value: String
    ): String {

        return value
            .trim()
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
                "|",
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
            .take(20)
            .ifBlank {
                "未命名用户"
            }
    }

    /**
     * 坐标格式化。
     */
    private fun formatCoordinate(
        value: Double
    ): String {

        return String.format(
            java.util.Locale.US,
            "%.6f",
            value
        )
    }
}