package main

import (
	"fmt"
	"math"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// ============================================================
	// WALKIE 成员位置
	// ============================================================

	// 手机 -> VPS
	//
	// WALKIE_LOCATION:latitude:longitude:timestamp
	//
	// timestamp 支持：
	// Unix 秒
	// Unix 毫秒
	msgLocation = "WALKIE_LOCATION"

	// VPS -> 同频道成员
	//
	// WALKIE_MEMBER_LOCATION:
	// channel:userid:username:latitude:longitude:timestamp
	msgMemberLocation = "WALKIE_MEMBER_LOCATION"

	// 位置广播周期。
	//
	// 即使用户没有刚刚发送位置，
	// 也会定期把当前有效位置重新同步给同频道成员。
	locationBroadcastInterval = 5 * time.Second

	// 超过这个时间没有新的定位，
	// VPS 不再向其他成员广播这个人的位置。
	//
	// 注意：
	// 这里不会把 Client 从在线列表删除。
	// 在线状态仍由原来的 clientTimeout 管理。
	locationStaleAfter = 90 * time.Second
)

// ============================================================
// 成员位置
// ============================================================

type MemberLocation struct {
	UserID      string
	Username    string
	ChannelName string

	Latitude  float64
	Longitude float64

	// 手机上报的时间。
	Timestamp time.Time

	// VPS真正收到这次定位的时间。
	UpdatedAt time.Time
}

// ============================================================
// 全局位置缓存
// ============================================================
//
// 当前项目最大在线客户端只有32个，
// 所以位置缓存非常小。
//
// 位置以UserID作为唯一键。
// ============================================================

var (
	memberLocationMu sync.RWMutex

	memberLocations = make(
		map[string]MemberLocation,
	)
)

// ============================================================
// 处理手机位置
// ============================================================
//
// 输入：
//
// WALKIE_LOCATION:latitude:longitude:timestamp
//
// 示例：
//
// WALKIE_LOCATION:29.563012:106.551556:1788524600000
//
// VPS不会相信客户端自己提供的UserID，
// 而是通过UDP地址找到已经登录的Client，
// 再使用服务器保存的UserID。
// ============================================================

func (s *Server) handleLocationUpdate(
	addr *net.UDPAddr,
	text string,
) {

	if addr == nil {
		return
	}

	prefix := msgLocation + ":"

	if !strings.HasPrefix(
		text,
		prefix,
	) {
		return
	}

	payload := strings.TrimPrefix(
		text,
		prefix,
	)

	parts := strings.SplitN(
		payload,
		":",
		3,
	)

	if len(parts) != 3 {

		logLocation(
			"位置数据格式错误 addr=%s data=%s",
			addr.String(),
			text,
		)

		return
	}

	latitude, err := strconv.ParseFloat(
		strings.TrimSpace(parts[0]),
		64,
	)

	if err != nil {

		logLocation(
			"纬度解析失败 addr=%s value=%s",
			addr.String(),
			parts[0],
		)

		return
	}

	longitude, err := strconv.ParseFloat(
		strings.TrimSpace(parts[1]),
		64,
	)

	if err != nil {

		logLocation(
			"经度解析失败 addr=%s value=%s",
			addr.String(),
			parts[1],
		)

		return
	}

	if math.IsNaN(latitude) ||
		math.IsInf(latitude, 0) ||
		math.IsNaN(longitude) ||
		math.IsInf(longitude, 0) {

		logLocation(
			"位置包含非法浮点值 addr=%s",
			addr.String(),
		)

		return
	}

	if latitude < -90.0 ||
		latitude > 90.0 {

		logLocation(
			"纬度超出范围 addr=%s latitude=%f",
			addr.String(),
			latitude,
		)

		return
	}

	if longitude < -180.0 ||
		longitude > 180.0 {

		logLocation(
			"经度超出范围 addr=%s longitude=%f",
			addr.String(),
			longitude,
		)

		return
	}

	timestamp, valid :=
		parseLocationTimestamp(
			strings.TrimSpace(parts[2]),
		)

	if !valid {

		/*
		 * 手机时间异常时，
		 * 不让一次错误时间直接导致位置丢失。
		 *
		 * 直接使用VPS当前时间作为本次上报时间。
		 */
		timestamp =
			time.Now()
	}

	now := time.Now()

	// ============================================================
	// 根据当前UDP地址找到正式Client
	// ============================================================

	s.mu.RLock()

	client :=
		s.clients[addr.String()]

	if client != nil {

		/*
		 * 复制需要的数据，
		 * 退出锁以后再操作位置缓存。
		 */
		userID :=
			client.UserID

		username :=
			client.Username

		channelName :=
			client.ChannelName

		s.mu.RUnlock()

		if userID == "" ||
			channelName == "" {

			return
		}

		memberLocationMu.Lock()

		memberLocations[userID] =
			MemberLocation{
				UserID:      userID,
				Username:    username,
				ChannelName: channelName,
				Latitude:    latitude,
				Longitude:   longitude,
				Timestamp:   timestamp,
				UpdatedAt:   now,
			}

		memberLocationMu.Unlock()

		logLocation(
			"位置更新: userID=%s username=%s channel=%s lat=%.6f lon=%.6f",
			userID,
			username,
			channelName,
			latitude,
			longitude,
		)

		/*
		 * 位置更新以后立即发送一次，
		 * 不等待5秒周期。
		 */
		s.broadcastMemberLocations(
			channelName,
		)

		return
	}

	s.mu.RUnlock()

	/*
	 * 未登录用户发送位置，
	 * 直接忽略。
	 */
	logLocation(
		"忽略未登录位置 addr=%s",
		addr.String(),
	)
}

// ============================================================
// 解析时间戳
// ============================================================

func parseLocationTimestamp(
	value string,
) (time.Time, bool) {

	if value == "" {
		return time.Now(), false
	}

	number, err := strconv.ParseInt(
		value,
		10,
		64,
	)

	if err != nil {
		return time.Now(), false
	}

	/*
	 * 13位左右一般是Unix毫秒。
	 * 10位左右一般是Unix秒。
	 */
	if number >= 1_000_000_000_000 {

		return time.UnixMilli(
			number,
		), true
	}

	if number >= 1_000_000_000 {

		return time.Unix(
			number,
			0,
		), true
	}

	return time.Now(), false
}

// ============================================================
// 定时位置广播
// ============================================================

func (s *Server) locationBroadcastLoop() {

	ticker := time.NewTicker(
		locationBroadcastInterval,
	)

	defer ticker.Stop()

	for range ticker.C {

		channels :=
			s.getActiveLocationChannels()

		for _, channelName :=
			range channels {

			s.broadcastMemberLocations(
				channelName,
			)
		}
	}
}

// ============================================================
// 获取当前活跃频道
// ============================================================

func (s *Server) getActiveLocationChannels() []string {

	channelSet :=
		make(map[string]struct{})

	now :=
		time.Now()

	s.mu.RLock()

	for _, client :=
		range s.clients {

		if client == nil {
			continue
		}

		if client.ChannelName == "" {
			continue
		}

		if now.Sub(
			client.LastSeen,
		) > clientTimeout {

			continue
		}

		channelSet[
			client.ChannelName,
		] = struct{}{}
	}

	s.mu.RUnlock()

	result :=
		make([]string, 0, len(channelSet))

	for channelName :=
		range channelSet {

		result =
			append(
				result,
				channelName,
			)
	}

	return result
}

// ============================================================
// 广播同频道成员位置
// ============================================================
//
// 每个成员的位置单独发一个UDP包。
//
// 这样不会因为人数增加导致：
//
// 一个超大的UDP位置列表
//
// 更适合WALKIE当前UDP架构。
// ============================================================

func (s *Server) broadcastMemberLocations(
	channelName string,
) {

	if channelName == "" {
		return
	}

	now :=
		time.Now()

	// ============================================================
	// 找同频道在线客户端
	// ============================================================

	recipients :=
		make(
			[]*net.UDPAddr,
			0,
		)

	s.mu.RLock()

	for _, client :=
		range s.clients {

		if client == nil {
			continue
		}

		if client.ChannelName !=
			channelName {

			continue
		}

		if client.Addr == nil {
			continue
		}

		if now.Sub(
			client.LastSeen,
		) > clientTimeout {

			continue
		}

		recipients =
			append(
				recipients,
				cloneUDPAddr(
					client.Addr,
				),
			)
	}

	s.mu.RUnlock()

	if len(recipients) == 0 {
		return
	}

	// ============================================================
	// 找有效位置
	// ============================================================

	locations :=
		make(
			[]MemberLocation,
			0,
		)

	memberLocationMu.RLock()

	for _, location :=
		range memberLocations {

		if location.ChannelName !=
			channelName {

			continue
		}

		if location.UserID == "" {
			continue
		}

		/*
		 * 位置太久没有更新，
		 * 不再广播。
		 */
		if now.Sub(
			location.UpdatedAt,
		) > locationStaleAfter {

			continue
		}

		locations =
			append(
				locations,
				location,
			)
	}

	memberLocationMu.RUnlock()

	if len(locations) == 0 {
		return
	}

	// ============================================================
	// 每个人的位置单独广播
	// ============================================================

	for _, location :=
		range locations {

		username :=
			sanitizeLocationUsername(
				location.Username,
			)

		message :=
			fmt.Sprintf(
				"%s:%s:%s:%s:%.6f:%.6f:%d",
				msgMemberLocation,
				channelName,
				location.UserID,
				username,
				location.Latitude,
				location.Longitude,
				location.Timestamp.UnixMilli(),
			)

		for _, recipient :=
			range recipients {

			s.sendMessage(
				recipient,
				message,
			)
		}
	}

	logLocation(
		"位置广播: channel=%s locations=%d recipients=%d",
		channelName,
		len(locations),
		len(recipients),
	)
}

// ============================================================
// 清理单个用户位置
// ============================================================
//
// 当前暂时不直接删除。
// 因为在线状态和位置状态是两个独立概念。
//
// 后面如果用户正式退出，
// 可以由removeClient主动调用这个函数。
// ============================================================

func removeMemberLocation(
	userID string,
) {

	if userID == "" {
		return
	}

	memberLocationMu.Lock()

	delete(
		memberLocations,
		userID,
	)

	memberLocationMu.Unlock()
}

// ============================================================
// 名字保护
// ============================================================
//
// 协议使用 : 分隔，
// 所以昵称里不能直接出现 :。
//
// 为了避免破坏现有协议，
// 这里只把可能破坏位置协议的字符替换掉。
//
// 原昵称不会影响登录本身。
// ============================================================

func sanitizeLocationUsername(
	username string,
) string {

	username =
		strings.TrimSpace(
			username,
		)

	username =
		strings.ReplaceAll(
			username,
			":",
			"_",
		)

	username =
		strings.ReplaceAll(
			username,
			";",
			"_",
		)

	username =
		strings.ReplaceAll(
			username,
			"|",
			"_",
		)

	if username == "" {
		username = "未命名用户"
	}

	return username
}

// ============================================================
// 位置日志
// ============================================================

func logLocation(
	format string,
	args ...interface{},
) {

	log.Printf(
		"WALKIE LOCATION: "+format,
		args...,
	)
}