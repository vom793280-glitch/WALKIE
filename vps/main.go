package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"math"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// ============================================================
	// WALKIE V24.9.2
	// ============================================================

	serverVersion = "WALKIE V24.9.2"

	listenAddr  = ":50000"
	maxClients  = 32
	maxChannels = 32
	maxUsers    = 128

	// UDP 心跳 5 秒一次。
	// 90 秒没有任何客户端数据才清理。
	clientTimeout = 90 * time.Second

	talkTimeout = 30 * time.Second

	maxPacketSize = 1500

	maxConnectionsPerIP = 999

	defaultChannel = "public"

	// ============================================================
	// UDP 缓冲
	// ============================================================

	// VPS 小型实时对讲场景：
	// 增大内核 UDP 缓冲，减少短时间突发导致的内核丢包。
	udpReadBufferSize  = 1024 * 1024
	udpWriteBufferSize = 1024 * 1024

	// ============================================================
	// 匿名/临时客户端
	// ============================================================

	/*
	 * 新版正式 Android 都会携带 DeviceID。
	 *
	 * DeviceID 为空的客户端只作为兼容旧设备存在，
	 * 但不能让这类临时连接无限期占用客户端槽位。
	 *
	 * 30 秒后，如果仍然没有升级成正式 DeviceID 客户端，
	 * cleanupLoop 会清除。
	 */
	anonymousClientTimeout = 30 * time.Second

	// ============================================================
	// 基础协议
	// ============================================================

	msgHello     = "WALKIE_HELLO"
	msgConnected = "WALKIE_CONNECTED"
	msgKeepAlive = "WALKIE_KEEPALIVE"
	msgGoodbye   = "WALKIE_GOODBYE"
	msgNetPing   = "WALKIE_NET_PING"
	msgNetPong   = "WALKIE_NET_PONG"

	// ============================================================
	// 抢麦
	// ============================================================

	msgTalkStart   = "WALKIE_TALK_START"
	msgTalkStop    = "WALKIE_TALK_STOP"
	msgTalkOK      = "WALKIE_TALK_OK"
	msgTalkBusy    = "WALKIE_TALK_BUSY"
	msgTalkRelease = "WALKIE_TALK_RELEASED"

	msgTalkBroadcast = "WALKIE_TALKING"

	// ============================================================
	// 用户
	// ============================================================

	msgUserOK     = "WALKIE_USER_OK"
	msgUserStatus = "WALKIE_USER_STATUS"

	/*
	 * V20：
	 *
	 * WALKIE_SET_NICKNAME:昵称
	 */
	msgSetNickname = "WALKIE_SET_NICKNAME"

	/*
	 * V20：
	 *
	 * WALKIE_USER_LIST:频道:userid|nickname;userid|nickname
	 */
	msgUserList = "WALKIE_USER_LIST"

	// ============================================================
	// 频道
	// ============================================================

	msgChannelList    = "WALKIE_CHANNEL_LIST"
	msgChannelJoined  = "WALKIE_CHANNEL_JOINED"
	msgChannelLeft    = "WALKIE_CHANNEL_LEFT"
	msgChannelCreated = "WALKIE_CHANNEL_CREATED"
	msgChannelDeleted = "WALKIE_CHANNEL_DELETED"
	msgChannelError   = "WALKIE_CHANNEL_ERROR"
	msgChannelInfo    = "WALKIE_CHANNEL_INFO"

	msgChannelMembers = "WALKIE_CHANNEL_MEMBERS"

	channelPublic  = "PUBLIC"
	channelPrivate = "PRIVATE"

	// ============================================================
	// 成员位置
	// ============================================================

	/*
	 * 手机 -> VPS
	 *
	 * WALKIE_LOCATION:latitude:longitude:timestamp
	 */
	msgLocation = "WALKIE_LOCATION"

	/*
	 * VPS -> 当前频道所有成员
	 *
	 * WALKIE_MEMBER_LOCATION:
	 * channel:
	 * userid:
	 * username:
	 * latitude:
	 * longitude:
	 * timestamp
	 */
	msgMemberLocation = "WALKIE_MEMBER_LOCATION"

	locationStaleAfter = 90 * time.Second

	locationBroadcastInterval = 5 * time.Second
)

// ============================================================
// 客户端
// ============================================================

type Client struct {
	Addr        *net.UDPAddr
	LastSeen    time.Time
	UserID      string
	Username    string
	ChannelName string
	IP          string
	DeviceID    string
}

// ============================================================
// Session
// ============================================================

type Session struct {
	UserID      string
	Username    string
	ChannelName string
	LastSeen    time.Time
	IP          string
	DeviceID    string
}

// ============================================================
// 频道
// ============================================================

type Channel struct {
	Name        string
	CreatorID   string
	CreatedAt   time.Time
	ChannelType string
	Password    string
}

// ============================================================
// 抢麦
// ============================================================

type TalkState struct {
	Addr      *net.UDPAddr
	UserID    string
	Username  string
	IP        string
	DeviceID  string
	StartTime time.Time
}

// ============================================================
// 成员位置
// ============================================================

type MemberLocation struct {
	UserID      string
	Username    string
	ChannelName string
	Latitude    float64
	Longitude   float64
	Timestamp   int64
	UpdatedAt   time.Time
}

// ============================================================
// 统计
// ============================================================

type AudioStats struct {
	ReceivedPackets  uint64
	ReceivedBytes    uint64
	ForwardedPackets uint64
	ForwardedBytes   uint64
	DroppedPackets   uint64
	DroppedBytes     uint64
	InvalidPackets   uint64
	LastReport       time.Time
}

// ============================================================
// 服务器
// ============================================================

type Server struct {
	conn *net.UDPConn
	mu   sync.RWMutex

	clients  map[string]*Client
	users    map[string]*Client
	channels map[string]*Channel
	talkers  map[string]*TalkState
	sessions map[string]*Session

	ipConnections map[string]int

	memberLocations map[string]MemberLocation

	stats AudioStats
}

// ============================================================
// main
// ============================================================

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	addr, err := net.ResolveUDPAddr(
		"udp",
		listenAddr,
	)
	if err != nil {
		log.Fatal("解析 UDP 地址失败:", err)
	}

	conn, err := net.ListenUDP(
		"udp",
		addr,
	)
	if err != nil {
		log.Fatal("监听 UDP 50000 失败:", err)
	}

	defer conn.Close()

	// ============================================================
	// 增大 UDP 内核缓冲
	// ============================================================

	if err := conn.SetReadBuffer(udpReadBufferSize); err != nil {
		log.Println("设置 UDP 接收缓冲失败:", err)
	}

	if err := conn.SetWriteBuffer(udpWriteBufferSize); err != nil {
		log.Println("设置 UDP 发送缓冲失败:", err)
	}

	server := &Server{
		conn:            conn,
		clients:         make(map[string]*Client),
		users:           make(map[string]*Client),
		channels:        make(map[string]*Channel),
		talkers:         make(map[string]*TalkState),
		sessions:        make(map[string]*Session),
		ipConnections:   make(map[string]int),
		memberLocations: make(map[string]MemberLocation),
		stats: AudioStats{
			LastReport: time.Now(),
		},
	}

	server.channels[defaultChannel] = &Channel{
		Name:        defaultChannel,
		CreatorID:   "system",
		CreatedAt:   time.Now(),
		ChannelType: channelPublic,
		Password:    "",
	}

	log.Println("========================================")
	log.Println(serverVersion)
	log.Println("========================================")
	log.Println("UDP监听:", listenAddr)
	log.Println("最大在线客户端:", maxClients)
	log.Println("最大频道:", maxChannels)
	log.Println("最大用户:", maxUsers)
	log.Println("客户端超时:", clientTimeout)
	log.Println("匿名客户端超时:", anonymousClientTimeout)
	log.Println("讲话超时:", talkTimeout)
	log.Println("UDP最大包:", maxPacketSize)
	log.Println("UDP接收缓冲:", udpReadBufferSize)
	log.Println("UDP发送缓冲:", udpWriteBufferSize)
	log.Println("单IP连接限制:", maxConnectionsPerIP)
	log.Println("默认频道:", defaultChannel)
	log.Println("公开频道: 已启用")
	log.Println("私密频道: 已启用")
	log.Println("频道密码: 已启用")
	log.Println("在线人数实时同步: 已启用")
	log.Println("设备ID稳定会话: 已启用")
	log.Println("重复设备连接清理: 已启用")
	log.Println("KEEPALIVE应答: 已启用")
	log.Println("KEEPALIVE DeviceID兼容: 已启用")
	log.Println("频道成员列表: 已启用")
	log.Println("在线人员昵称列表: 已启用")
	log.Println("昵称同步: 已启用")
	log.Println("频道人数实时同步: 已启用")
	log.Println("抢麦广播: 已启用")
	log.Println("抢麦自动释放: 已启用")
	log.Println("断线释放麦权: 已启用")
	log.Println("UDP异常包过滤: 已启用")
	log.Println("音频统计: 已启用")
	log.Println("后台重连恢复频道: 已启用")
	log.Println("移动网络抢麦端口跟随: 已启用")
	log.Println("持麦期间UDP端口自动迁移: 已启用")
	log.Println("同NAT多设备保护: 已启用")
	log.Println("V22/V23.1 音频透明兼容转发: 已启用")
	log.Println("成员GPS实时位置: 已启用")
	log.Println("成员GPS位置5秒广播: 已启用")
	log.Println("成员GPS位置90秒过期: 已启用")
	log.Println("========================================")
	log.Println("服务器已启动，等待手机连接...")
	log.Println("========================================")

	go server.talkTimeoutLoop()
	go server.cleanupLoop()
	go server.statsLoop()
	go server.locationBroadcastLoop()

	server.run()
}

// ============================================================
// UDP 主循环
// ============================================================

func (s *Server) run() {
	buffer := make([]byte, 65535)

	for {
		n, remoteAddr, err := s.conn.ReadFromUDP(buffer)
		if err != nil {
			log.Println("读取 UDP 数据失败:", err)
			continue
		}

		if remoteAddr == nil || n <= 0 {
			continue
		}

		if n > maxPacketSize {
			s.recordDrop(uint64(n), true)

			log.Printf(
				"丢弃超大 UDP 包: %s size=%d",
				remoteAddr.String(),
				n,
			)

			continue
		}

		data := make([]byte, n)
		copy(data, buffer[:n])

		text := string(data)

		// ============================================================
		// HELLO
		// ============================================================

		if strings.HasPrefix(text, msgHello) {
			deviceID := ""

			if strings.HasPrefix(text, msgHello+":") {
				deviceID = strings.TrimSpace(
					strings.TrimPrefix(
						text,
						msgHello+":",
					),
				)
			}

			client, accepted := s.updateClient(
				remoteAddr,
				deviceID,
			)

			if !accepted || client == nil {
				continue
			}

			log.Printf(
				"HELLO: %s device=%s user=%s channel=%s",
				remoteAddr.String(),
				client.DeviceID,
				client.Username,
				client.ChannelName,
			)

			s.sendMessage(
				remoteAddr,
				msgConnected,
			)

			s.sendUserStatusToClient(
				remoteAddr,
			)

			s.sendChannelInfoToClient(
				remoteAddr,
			)

			s.sendUserListToClient(
				remoteAddr,
				client.ChannelName,
			)

			s.broadcastChannelList()

			s.broadcastChannelMembers(
				client.ChannelName,
			)

			s.sendMemberLocationsToClient(
				remoteAddr,
				client.ChannelName,
			)

			continue
		}

		// ============================================================
		// KEEP ALIVE
		// ============================================================

		/*
		 * V24.9.2 修复：
		 *
		 * Android 当前实际发送：
		 *
		 * WALKIE_KEEPALIVE:DeviceID
		 *
		 * 旧 VPS 只判断：
		 *
		 * WALKIE_KEEPALIVE
		 *
		 * 导致带 DeviceID 的 KEEPALIVE 无法更新 LastSeen，
		 * 最终客户端被 90 秒超时清理。
		 *
		 * 现在两种格式全部兼容。
		 */

		if text == msgKeepAlive ||
			strings.HasPrefix(
				text,
				msgKeepAlive+":",
			) {

			deviceID := ""

			if strings.HasPrefix(
				text,
				msgKeepAlive+":",
			) {

				deviceID =
					strings.TrimSpace(
						strings.TrimPrefix(
							text,
							msgKeepAlive+":",
						),
					)
			}

			var client *Client
			var accepted bool

			if deviceID != "" {

				client, accepted =
					s.updateClient(
						remoteAddr,
						deviceID,
					)

			} else {

				/*
				 * 不带 DeviceID：
				 *
				 * 只允许已存在地址刷新。
				 *
				 * 不因为一个陌生 KEEPALIVE
				 * 创建新的匿名 Client。
				 */
				client, accepted =
					s.touchKnownClient(
						remoteAddr,
					)
			}

			if !accepted ||
				client == nil {

				continue
			}

			s.sendMessage(
				remoteAddr,
				msgKeepAlive,
			)

			continue
		}

		// ============================================================
		// 网络探测
		// ============================================================

		if strings.HasPrefix(
			text,
			msgNetPing+":",
		) {

			payload :=
				strings.TrimPrefix(
					text,
					msgNetPing+":",
				)

			parts :=
				strings.SplitN(
					payload,
					":",
					2,
				)

			if len(parts) >= 1 {

				sequence :=
					strings.TrimSpace(
						parts[0],
					)

				timestamp := ""

				if len(parts) >= 2 {

					timestamp =
						strings.TrimSpace(
							parts[1],
						)
				}

				s.sendMessage(
					remoteAddr,
					msgNetPong+
						":"+
						sequence+
						":"+
						timestamp,
				)
			}

			continue
		}

		// ============================================================
		// 成员位置
		// ============================================================

		if strings.HasPrefix(
			text,
			msgLocation+":",
		) {

			s.handleLocationUpdate(
				remoteAddr,
				text,
			)

			continue
		}

		// ============================================================
		// GOODBYE
		// ============================================================

		if text == msgGoodbye {
			channelName := s.removeClient(
				remoteAddr,
			)

			log.Printf(
				"客户端断开: %s",
				remoteAddr.String(),
			)

			s.broadcastChannelList()

			if channelName != "" {
				s.broadcastChannelMembers(
					channelName,
				)

				s.broadcastMemberLocations(
					channelName,
				)
			}

			continue
		}

		// ============================================================
		// 登录
		// ============================================================

		if strings.HasPrefix(
			text,
			"WALKIE_LOGIN:",
		) {
			payload := strings.TrimPrefix(
				text,
				"WALKIE_LOGIN:",
			)

			parts := strings.SplitN(
				payload,
				":",
				2,
			)

			deviceID := ""
			username := ""

			if len(parts) >= 1 {
				deviceID =
					strings.TrimSpace(
						parts[0],
					)
			}

			if len(parts) >= 2 {
				username =
					strings.TrimSpace(
						parts[1],
					)
			}

			s.handleLogin(
				remoteAddr,
				deviceID,
				username,
			)

			continue
		}

		// ============================================================
		// 设置昵称
		// ============================================================

		if strings.HasPrefix(
			text,
			msgSetNickname+":",
		) {

			username :=
				strings.TrimPrefix(
					text,
					msgSetNickname+":",
				)

			s.handleSetNickname(
				remoteAddr,
				username,
			)

			continue
		}

		// ============================================================
		// 用户列表
		// ============================================================

		if text == msgUserList {
			client, accepted :=
				s.touchKnownClient(
					remoteAddr,
				)

			if accepted &&
				client != nil {

				s.sendUserListToClient(
					remoteAddr,
					client.ChannelName,
				)

				s.sendMemberLocationsToClient(
					remoteAddr,
					client.ChannelName,
				)
			}

			continue
		}

		// ============================================================
		// 频道列表
		// ============================================================

		if text == msgChannelList {
			client, accepted :=
				s.touchKnownClient(
					remoteAddr,
				)

			if accepted &&
				client != nil {

				s.handleChannelList(
					remoteAddr,
				)
			}

			continue
		}

		// ============================================================
		// 创建频道
		// ============================================================

		if strings.HasPrefix(
			text,
			"WALKIE_CREATE_CHANNEL:",
		) {
			payload :=
				strings.TrimPrefix(
					text,
					"WALKIE_CREATE_CHANNEL:",
				)

			if _, accepted :=
				s.touchKnownClient(
					remoteAddr,
				); !accepted {

				continue
			}

			s.handleCreateChannel(
				remoteAddr,
				payload,
			)

			continue
		}

		// ============================================================
		// 加入频道
		// ============================================================

		if strings.HasPrefix(
			text,
			"WALKIE_JOIN_CHANNEL:",
		) {
			payload :=
				strings.TrimPrefix(
					text,
					"WALKIE_JOIN_CHANNEL:",
				)

			if !s.ensureKnownClientForControl(
				remoteAddr,
			) {
				continue
			}

			s.handleJoinChannel(
				remoteAddr,
				payload,
			)

			continue
		}

		// ============================================================
		// 删除频道
		// ============================================================

		if strings.HasPrefix(
			text,
			"WALKIE_DELETE_CHANNEL:",
		) {
			channelName :=
				strings.TrimPrefix(
					text,
					"WALKIE_DELETE_CHANNEL:",
				)

			if !s.ensureKnownClientForControl(
				remoteAddr,
			) {
				continue
			}

			s.handleDeleteChannel(
				remoteAddr,
				channelName,
			)

			continue
		}

		// ============================================================
		// 离开频道
		// ============================================================

		if text == "WALKIE_LEAVE_CHANNEL" {

			if !s.ensureKnownClientForControl(
				remoteAddr,
			) {
				continue
			}

			s.handleLeaveChannel(
				remoteAddr,
			)

			continue
		}

		// ============================================================
		// 当前频道信息
		// ============================================================

		if text == msgChannelInfo {

			if !s.ensureKnownClientForControl(
				remoteAddr,
			) {
				continue
			}

			s.handleChannelInfo(
				remoteAddr,
			)

			continue
		}

		// ============================================================
		// 当前频道成员
		// ============================================================

		if text == msgChannelMembers {
			client, accepted :=
				s.touchKnownClient(
					remoteAddr,
				)

			if accepted &&
				client != nil {

				s.sendChannelMembers(
					remoteAddr,
					client.ChannelName,
				)

				s.sendUserListToClient(
					remoteAddr,
					client.ChannelName,
				)

				s.sendMemberLocationsToClient(
					remoteAddr,
					client.ChannelName,
				)
			}

			continue
		}

		// ============================================================
		// 抢麦
		// ============================================================

		if text == msgTalkStart {

			if !s.ensureKnownClientForControl(
				remoteAddr,
			) {
				continue
			}

			s.handleTalkStart(
				remoteAddr,
			)

			continue
		}

		/*
		 * 兼容：
		 *
		 * WALKIE_TALK_START:DeviceID
		 *
		 * 新旧 Android 都支持。
		 */
		if strings.HasPrefix(
			text,
			msgTalkStart+":",
		) {

			deviceID :=
				strings.TrimSpace(
					strings.TrimPrefix(
						text,
						msgTalkStart+":",
					),
				)

			if deviceID != "" {

				client, accepted :=
					s.updateClient(
						remoteAddr,
						deviceID,
					)

				if !accepted ||
					client == nil {

					continue
				}
			} else {

				if !s.ensureKnownClientForControl(
					remoteAddr,
				) {
					continue
				}
			}

			s.handleTalkStart(
				remoteAddr,
			)

			continue
		}

		// ============================================================
		// 释放麦权
		// ============================================================

		if text == msgTalkStop {

			if !s.ensureKnownClientForControl(
				remoteAddr,
			) {
				continue
			}

			s.handleTalkStop(
				remoteAddr,
			)

			continue
		}

		// ============================================================
		// 音频
		// ============================================================

		s.mu.RLock()

		senderClient :=
			s.clients[remoteAddr.String()]

		s.mu.RUnlock()

		/*
		 * 未知 UDP 地址不能因为一个普通音频包
		 * 就创建新的匿名 Client。
		 *
		 * 只有：
		 *
		 * 1. 已知 Client
		 * 2. 当前持麦者发生 NAT / 移动网络端口变化
		 *
		 * 才允许继续。
		 */
		if senderClient == nil {

			if !s.migrateTalkerByAddress(
				remoteAddr,
			) {

				s.recordDrop(
					uint64(n),
					true,
				)

				continue
			}

			s.mu.RLock()

			senderClient =
				s.clients[remoteAddr.String()]

			s.mu.RUnlock()

			if senderClient == nil {

				s.recordDrop(
					uint64(n),
					true,
				)

				continue
			}
		}

		s.recordReceived(
			uint64(n),
		)

		s.relayAudio(
			data,
			remoteAddr,
		)
	}
}

// ============================================================
// 发送消息
// ============================================================

func (s *Server) sendMessage(
	addr *net.UDPAddr,
	message string,
) {
	if addr == nil {
		return
	}

	if _, err := s.conn.WriteToUDP(
		[]byte(message),
		addr,
	); err != nil {

		log.Printf(
			"发送消息失败: %s -> %v",
			addr.String(),
			err,
		)
	}
}

// ============================================================
// 已知客户端刷新
// ============================================================

func (s *Server) touchKnownClient(
	addr *net.UDPAddr,
) (*Client, bool) {

	if addr == nil {
		return nil, false
	}

	key :=
		addr.String()

	now :=
		time.Now()

	s.mu.Lock()

	client, exists :=
		s.clients[key]

	if !exists ||
		client == nil {

		s.mu.Unlock()

		return nil, false
	}

	client.LastSeen =
		now

	if client.ChannelName == "" {
		client.ChannelName =
			defaultChannel
	}

	s.saveSessionLocked(
		client,
	)

	s.mu.Unlock()

	return client, true
}

// ============================================================
// 控制消息：确保地址对应正式客户端
// ============================================================

func (s *Server) ensureKnownClientForControl(
	addr *net.UDPAddr,
) bool {

	if addr == nil {
		return false
	}

	if _, accepted :=
		s.touchKnownClient(
			addr,
		); accepted {

		return true
	}

	/*
	 * 地址发生变化时：
	 *
	 * 先尝试利用“唯一公网 IP 客户端”
	 * 做安全迁移。
	 *
	 * 注意：
	 * 如果同一个公网 IP 后面有多个 WALKIE 客户端，
	 * 这里绝不会猜测是谁。
	 *
	 * 这就是对你现在：
	 *
	 * 183.228.37.177:多个UDP端口
	 *
	 * 情况的保护。
	 */
	if s.migrateClientByUniqueIP(
		addr,
	) {

		_, accepted :=
			s.touchKnownClient(
				addr,
			)

		return accepted
	}

	return false
}

// ============================================================
// 根据唯一公网 IP 迁移普通客户端
// ============================================================

func (s *Server) migrateClientByUniqueIP(
	addr *net.UDPAddr,
) bool {

	if addr == nil {
		return false
	}

	key :=
		addr.String()

	ip :=
		addr.IP.String()

	now :=
		time.Now()

	s.mu.Lock()

	/*
	 * 当前地址已经存在。
	 */
	if _, exists :=
		s.clients[key]; exists {

		s.mu.Unlock()

		return true
	}

	var candidateKey string
	var candidate *Client
	activeCount := 0

	for oldKey, client := range s.clients {

		if client == nil ||
			client.Addr == nil {

			continue
		}

		if client.IP != ip {
			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		activeCount++

		if activeCount > 1 {
			break
		}

		candidateKey =
			oldKey

		candidate =
			client
	}

	/*
	 * 同一个公网 IP 有多个在线设备：
	 *
	 * 不能猜是谁迁移过来的。
	 */
	if activeCount != 1 ||
		candidate == nil {

		s.mu.Unlock()

		return false
	}

	oldIP :=
		candidate.IP

	oldChannel :=
		candidate.ChannelName

	oldUserID :=
		candidate.UserID

	delete(
		s.clients,
		candidateKey,
	)

	candidate.Addr =
		cloneUDPAddr(addr)

	candidate.IP =
		ip

	candidate.LastSeen =
		now

	s.clients[key] =
		candidate

	if oldIP != "" &&
		s.ipConnections[oldIP] > 0 {

		s.ipConnections[oldIP]--

		if s.ipConnections[oldIP] <= 0 {

			delete(
				s.ipConnections,
				oldIP,
			)
		}
	}

	s.ipConnections[ip]++

	/*
	 * 同时更新正式用户表。
	 */
	if oldUserID != "" {
		s.users[oldUserID] =
			candidate
	}

	/*
	 * 如果这个用户当前正在讲话，
	 * 麦权地址也跟着迁移。
	 */
	if talker, ok :=
		s.talkers[oldChannel]; ok {

		if talker.UserID ==
			oldUserID {

			talker.Addr =
				cloneUDPAddr(addr)

			talker.IP =
				ip
		}
	}

	s.saveSessionLocked(
		candidate,
	)

	s.mu.Unlock()

	log.Printf(
		"安全UDP地址迁移: old=%s new=%s ip=%s user=%s userID=%s channel=%s",
		candidateKey,
		key,
		ip,
		candidate.Username,
		candidate.UserID,
		candidate.ChannelName,
	)

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		oldChannel,
	)

	s.broadcastMemberLocations(
		oldChannel,
	)

	return true
}

// ============================================================
// 更新 / 创建客户端
// ============================================================

func (s *Server) updateClient(
	addr *net.UDPAddr,
	deviceIDs ...string,
) (*Client, bool) {

	deviceID := ""

	if len(deviceIDs) > 0 {
		deviceID =
			strings.TrimSpace(
				deviceIDs[0],
			)
	}

	if addr == nil {
		return nil, false
	}

	key := addr.String()
	ip := addr.IP.String()
	now := time.Now()

	var migratedOldKey string
	var migratedChannel string

	s.mu.Lock()

	// ============================================================
	// 1. 当前 UDP 地址已经存在
	// ============================================================

	if client, exists :=
		s.clients[key]; exists {

		client.LastSeen =
			now

		if client.ChannelName == "" {
			client.ChannelName =
				defaultChannel
		}

		if client.Username == "" {
			client.Username =
				fmt.Sprintf(
					"USER-%d",
					addr.Port,
				)
		}

		if deviceID != "" {

			duplicateKey := ""
			var duplicate *Client

			for k, other := range s.clients {

				if k == key ||
					other == nil {

					continue
				}

				if other.DeviceID != "" &&
					other.DeviceID == deviceID {

					duplicateKey =
						k

					duplicate =
						other

					break
				}
			}

			if duplicate != nil {

				client.UserID =
					duplicate.UserID

				client.Username =
					duplicate.Username

				if duplicate.ChannelName != "" {
					client.ChannelName =
						duplicate.ChannelName
				}

				client.DeviceID =
					deviceID

				delete(
					s.clients,
					duplicateKey,
				)

				if duplicate.IP != "" &&
					s.ipConnections[duplicate.IP] > 0 {

					s.ipConnections[duplicate.IP]--

					if s.ipConnections[duplicate.IP] <= 0 {

						delete(
							s.ipConnections,
							duplicate.IP,
						)
					}
				}

				if duplicate.UserID != "" {

					if existing, exists :=
						s.users[duplicate.UserID]; exists &&
						existing == duplicate {

						delete(
							s.users,
							duplicate.UserID,
						)
					}
				}

				if talker, ok :=
					s.talkers[duplicate.ChannelName]; ok {

					if talker.Addr != nil &&
						talker.Addr.String() ==
							duplicateKey {

						talker.Addr =
							cloneUDPAddr(addr)

						talker.IP =
							ip

						talker.DeviceID =
							deviceID
					}
				}

				migratedOldKey =
					duplicateKey

				migratedChannel =
					client.ChannelName

			} else {

				client.DeviceID =
					deviceID
			}
		}

		s.users[client.UserID] =
			client

		s.saveSessionLocked(
			client,
		)

		s.mu.Unlock()

		if migratedOldKey != "" {

			log.Printf(
				"设备重连迁移: old=%s new=%s device=%s user=%s channel=%s",
				migratedOldKey,
				key,
				deviceID,
				client.Username,
				migratedChannel,
			)

			s.broadcastChannelList()

			s.broadcastChannelMembers(
				migratedChannel,
			)

			s.broadcastMemberLocations(
				migratedChannel,
			)
		}

		return client, true
	}

	// ============================================================
	// 2. DeviceID 优先寻找旧 UDP 端点
	// ============================================================

	if deviceID != "" {

		for oldKey, oldClient := range s.clients {

			if oldClient == nil ||
				oldClient.DeviceID != deviceID {

				continue
			}

			oldIP :=
				oldClient.IP

			oldChannel :=
				oldClient.ChannelName

			oldUserID :=
				oldClient.UserID

			oldClient.Addr =
				cloneUDPAddr(addr)

			oldClient.IP =
				ip

			oldClient.LastSeen =
				now

			oldClient.DeviceID =
				deviceID

			delete(
				s.clients,
				oldKey,
			)

			s.clients[key] =
				oldClient

			if oldIP != "" &&
				s.ipConnections[oldIP] > 0 {

				s.ipConnections[oldIP]--

				if s.ipConnections[oldIP] <= 0 {

					delete(
						s.ipConnections,
						oldIP,
					)
				}
			}

			s.ipConnections[ip]++

			if talker, ok :=
				s.talkers[oldChannel]; ok {

				if talker.UserID ==
					oldUserID {

					talker.Addr =
						cloneUDPAddr(
							addr,
						)

					talker.IP =
						ip

					talker.DeviceID =
						deviceID

					log.Printf(
						"持麦端口跟随设备迁移: old=%s new=%s device=%s user=%s channel=%s",
						oldKey,
						key,
						deviceID,
						oldClient.Username,
						oldChannel,
					)
				}
			}

			s.users[oldClient.UserID] =
				oldClient

			s.saveSessionLocked(
				oldClient,
			)

			s.mu.Unlock()

			log.Printf(
				"设备UDP端口迁移: old=%s new=%s device=%s user=%s channel=%s",
				oldKey,
				key,
				deviceID,
				oldClient.Username,
				oldClient.ChannelName,
			)

			s.broadcastChannelList()

			s.broadcastChannelMembers(
				oldChannel,
			)

			s.broadcastMemberLocations(
				oldChannel,
			)

			return oldClient, true
		}
	}

	// ============================================================
	// 3. 连接数量限制前先清理一次过期客户端
	// ============================================================

	s.pruneExpiredClientsLocked(now)

	if len(s.clients) >=
		maxClients {

		s.mu.Unlock()

		log.Printf(
			"拒绝新客户端: CLIENT_LIMIT addr=%s clients=%d max=%d",
			addr.String(),
			len(s.clients),
			maxClients,
		)

		s.sendMessage(
			addr,
			msgChannelError+":CLIENT_LIMIT",
		)

		return nil, false
	}

	userID :=
		makeUserID(
			ip,
			addr.Port,
			deviceID,
		)

	username :=
		fmt.Sprintf(
			"USER-%d",
			addr.Port,
		)

	channelName :=
		defaultChannel

	// ============================================================
	// 4. Session 恢复
	// ============================================================

	var oldSession *Session

	if deviceID != "" {

		if session :=
			s.sessions[sessionKey(deviceID)]; session != nil {

			oldSession =
				session
		}
	}

	if oldSession == nil {

		if session :=
			s.sessions[userID]; session != nil {

			oldSession =
				session
		}
	}

	client := &Client{
		Addr:        cloneUDPAddr(addr),
		LastSeen:    now,
		UserID:      userID,
		Username:    username,
		ChannelName: channelName,
		IP:          ip,
		DeviceID:    deviceID,
	}

	if oldSession != nil {

		if oldSession.UserID != "" {
			client.UserID =
				oldSession.UserID
		}

		if oldSession.Username != "" {
			client.Username =
				oldSession.Username
		}

		if oldSession.ChannelName != "" {
			client.ChannelName =
				oldSession.ChannelName
		}

		if oldSession.DeviceID != "" {
			client.DeviceID =
				oldSession.DeviceID
		}

		log.Printf(
			"恢复 Session: ip=%s device=%s userID=%s username=%s channel=%s",
			ip,
			deviceID,
			client.UserID,
			client.Username,
			client.ChannelName,
		)
	}

	// ============================================================
	// 5. 同 UserID 已在线
	// ============================================================

	if existing :=
		s.users[client.UserID]; existing != nil &&
		existing != client {

		oldKey := ""

		for k, other := range s.clients {

			if other == existing {
				oldKey =
					k
				break
			}
		}

		if oldKey != "" {

			oldIP :=
				existing.IP

			oldChannel :=
				existing.ChannelName

			existing.Addr =
				cloneUDPAddr(addr)

			existing.IP =
				ip

			existing.LastSeen =
				now

			existing.DeviceID =
				deviceID

			delete(
				s.clients,
				oldKey,
			)

			s.clients[key] =
				existing

			if oldIP != "" &&
				s.ipConnections[oldIP] > 0 {

				s.ipConnections[oldIP]--

				if s.ipConnections[oldIP] <= 0 {

					delete(
						s.ipConnections,
						oldIP,
					)
				}
			}

			s.ipConnections[ip]++

			if talker, ok :=
				s.talkers[oldChannel]; ok {

				if talker.UserID ==
					existing.UserID {

					talker.Addr =
						cloneUDPAddr(
							addr,
						)

					talker.IP =
						ip

					talker.DeviceID =
						deviceID

					log.Printf(
						"用户会话迁移同时更新持麦端口: old=%s new=%s user=%s channel=%s",
						oldKey,
						key,
						existing.Username,
						oldChannel,
					)
				}
			}

			s.saveSessionLocked(
				existing,
			)

			s.mu.Unlock()

			log.Printf(
				"用户会话迁移: old=%s new=%s userID=%s username=%s channel=%s",
				oldKey,
				key,
				existing.UserID,
				existing.Username,
				existing.ChannelName,
			)

			s.broadcastChannelList()

			s.broadcastChannelMembers(
				existing.ChannelName,
			)

			s.broadcastMemberLocations(
				existing.ChannelName,
			)

			return existing, true
		}
	}

	// ============================================================
	// 6. 用户数量限制
	// ============================================================

	if len(s.users) >=
		maxUsers {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":USER_LIMIT",
		)

		return nil, false
	}

	// ============================================================
	// 7. 正式创建客户端
	// ============================================================

	s.clients[key] =
		client

	s.users[client.UserID] =
		client

	s.ipConnections[ip]++

	s.saveSessionLocked(
		client,
	)

	s.mu.Unlock()

	log.Printf(
		"新客户端: addr=%s ip=%s userID=%s username=%s channel=%s device=%s",
		key,
		ip,
		client.UserID,
		client.Username,
		client.ChannelName,
		client.DeviceID,
	)

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		client.ChannelName,
	)

	s.broadcastMemberLocations(
		client.ChannelName,
	)

	return client, true
}

// ============================================================
// 清理过期客户端（调用方必须持有写锁）
// ============================================================

func (s *Server) pruneExpiredClientsLocked(
	now time.Time,
) {

	for key, client := range s.clients {

		if client == nil {

			delete(
				s.clients,
				key,
			)

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) <= timeout {

			continue
		}

		channelName :=
			client.ChannelName

		if talker, exists :=
			s.talkers[channelName]; exists {

			if talker.UserID ==
				client.UserID ||
				(talker.Addr != nil &&
					talker.Addr.String() == key) {

				delete(
					s.talkers,
					channelName,
				)
			}
		}

		if client.UserID != "" {

			s.removeMemberLocationLocked(
				client.UserID,
			)
		}

		delete(
			s.clients,
			key,
		)

		if client.IP != "" &&
			s.ipConnections[client.IP] > 0 {

			s.ipConnections[client.IP]--

			if s.ipConnections[client.IP] <= 0 {

				delete(
					s.ipConnections,
					client.IP,
				)
			}
		}

		if client.UserID != "" {

			if existing, exists :=
				s.users[client.UserID]; exists &&
				existing == client {

				delete(
					s.users,
					client.UserID,
				)
			}
		}
	}
}

// ============================================================
// 持麦用户 UDP 端口迁移
// ============================================================

func (s *Server) migrateTalkerByAddress(
	addr *net.UDPAddr,
) bool {

	if addr == nil {
		return false
	}

	ip :=
		addr.IP.String()

	key :=
		addr.String()

	now :=
		time.Now()

	type migration struct {
		oldKey   string
		channel  string
		username string
		userID   string
	}

	var migrated *migration

	s.mu.Lock()

	for channelName, talker := range s.talkers {

		if talker == nil ||
			talker.UserID == "" {

			continue
		}

		if talker.IP != ip {
			continue
		}

		if now.Sub(
			talker.StartTime,
		) >
			talkTimeout {

			continue
		}

		/*
		 * ========================================================
		 * V24.9.2 同 NAT 多设备保护
		 *
		 * 以前：
		 *
		 *   只要 talker.IP == remoteIP
		 *   就允许迁移。
		 *
		 * 这在：
		 *
		 *   手机A 183.228.37.177
		 *   手机B 183.228.37.177
		 *
		 * 的情况下存在误迁移风险。
		 *
		 * 现在：
		 *
		 *   只有同一个公网 IP 当前只有一个
		 *   活跃 WALKIE Client 时，
		 *   未知新端口才允许自动迁移。
		 *
		 * 如果同 IP 有多个设备：
		 *
		 *   不猜测。
		 *   必须依靠 DeviceID 的 HELLO / KEEPALIVE
		 *   完成正式端口迁移。
		 * ========================================================
		 */

		activeClientsSameIP :=
			0

		var ownerKey string
		var ownerClient *Client

		for clientKey, client := range s.clients {

			if client == nil ||
				client.Addr == nil {

				continue
			}

			if client.IP != ip {
				continue
			}

			timeout :=
				clientTimeout

			if client.DeviceID == "" {
				timeout =
					anonymousClientTimeout
			}

			if now.Sub(
				client.LastSeen,
			) > timeout {

				continue
			}

			activeClientsSameIP++

			ownerKey =
				clientKey

			ownerClient =
				client
		}

		if activeClientsSameIP != 1 ||
			ownerClient == nil {

			log.Printf(
				"拒绝模糊端口迁移: new=%s ip=%s 同IP在线客户端=%d talkUser=%s",
				key,
				ip,
				activeClientsSameIP,
				talker.Username,
			)

			s.mu.Unlock()

			return false
		}

		if ownerClient.UserID !=
			talker.UserID {

			log.Printf(
				"拒绝错误持麦迁移: new=%s ip=%s ownerUser=%s talkUser=%s",
				key,
				ip,
				ownerClient.Username,
				talker.Username,
			)

			s.mu.Unlock()

			return false
		}

		if ownerKey ==
			key {

			s.mu.Unlock()

			return true
		}

		oldIP :=
			ownerClient.IP

		oldChannel :=
			ownerClient.ChannelName

		delete(
			s.clients,
			ownerKey,
		)

		ownerClient.Addr =
			cloneUDPAddr(addr)

		ownerClient.IP =
			ip

		ownerClient.LastSeen =
			now

		s.clients[key] =
			ownerClient

		if oldIP != "" &&
			s.ipConnections[oldIP] > 0 {

			s.ipConnections[oldIP]--

			if s.ipConnections[oldIP] <= 0 {

				delete(
					s.ipConnections,
					oldIP,
				)
			}
		}

		s.ipConnections[ip]++

		talker.Addr =
			cloneUDPAddr(addr)

		talker.IP =
			ip

		talker.DeviceID =
			ownerClient.DeviceID

		ownerClient.LastSeen =
			now

		s.users[ownerClient.UserID] =
			ownerClient

		s.saveSessionLocked(
			ownerClient,
		)

		migrated =
			&migration{
				oldKey:   ownerKey,
				channel:  oldChannel,
				username: ownerClient.Username,
				userID:   ownerClient.UserID,
			}

		_ = channelName

		break
	}

	s.mu.Unlock()

	if migrated == nil {
		return false
	}

	log.Printf(
		"★持麦期间安全UDP端口迁移★ old=%s new=%s user=%s userID=%s channel=%s",
		migrated.oldKey,
		key,
		migrated.username,
		migrated.userID,
		migrated.channel,
	)

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		migrated.channel,
	)

	s.broadcastMemberLocations(
		migrated.channel,
	)

	return true
}

// ============================================================
// 保存 Session
// ============================================================

func (s *Server) saveSessionLocked(
	client *Client,
) {
	if client == nil {
		return
	}

	session :=
		&Session{
			UserID:      client.UserID,
			Username:    client.Username,
			ChannelName: client.ChannelName,
			LastSeen:    time.Now(),
			IP:          client.IP,
			DeviceID:    client.DeviceID,
		}

	s.sessions[client.UserID] = session

	if client.DeviceID != "" {

		s.sessions[sessionKey(
			client.DeviceID,
		)] = session
	}
}

// ============================================================
// Session key
// ============================================================

func sessionKey(
	deviceID string,
) string {

	return "device:" +
		deviceID
}

// ============================================================
// UserID
// ============================================================

func makeUserID(
	ip string,
	port int,
	deviceID string,
) string {

	if deviceID != "" {

		sum :=
			sha256.Sum256(
				[]byte(deviceID),
			)

		return "U-D-" +
			hex.EncodeToString(
				sum[:8],
			)
	}

	return fmt.Sprintf(
		"U-%s-%d",
		normalizeIP(ip),
		port,
	)
}

// ============================================================
// IP
// ============================================================

func normalizeIP(
	ip string,
) string {

	ip =
		strings.ReplaceAll(
			ip,
			":",
			"_",
		)

	ip =
		strings.ReplaceAll(
			ip,
			".",
			"_",
		)

	return ip
}

// ============================================================
// 删除客户端
// ============================================================

func (s *Server) removeClient(
	addr *net.UDPAddr,
) string {

	if addr == nil {
		return ""
	}

	key :=
		addr.String()

	s.mu.Lock()

	client, exists :=
		s.clients[key]

	if !exists {
		s.mu.Unlock()
		return ""
	}

	channelName :=
		client.ChannelName

	talkReleased :=
		false

	talkUsername :=
		client.Username

	if talker, exists :=
		s.talkers[channelName]; exists {

		if talker.UserID ==
			client.UserID ||
			(talker.Addr != nil &&
				talker.Addr.String() == key) {

			talkUsername =
				talker.Username

			delete(
				s.talkers,
				channelName,
			)

			talkReleased =
				true
		}
	}

	client.LastSeen =
		time.Now()

	s.saveSessionLocked(
		client,
	)

	s.removeMemberLocationLocked(
		client.UserID,
	)

	delete(
		s.clients,
		key,
	)

	if client.IP != "" &&
		s.ipConnections[client.IP] > 0 {

		s.ipConnections[client.IP]--

		if s.ipConnections[client.IP] <= 0 {

			delete(
				s.ipConnections,
				client.IP,
			)
		}
	}

	if client.UserID != "" {

		if existing, exists :=
			s.users[client.UserID]; exists &&
			existing == client {

			delete(
				s.users,
				client.UserID,
			)
		}
	}

	s.mu.Unlock()

	log.Printf(
		"客户端已删除: user=%s channel=%s addr=%s device=%s",
		client.Username,
		channelName,
		key,
		client.DeviceID,
	)

	if talkReleased {

		s.broadcastTalkReleased(
			channelName,
			talkUsername,
		)
	}

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		channelName,
	)

	s.broadcastMemberLocations(
		channelName,
	)

	return channelName
}

// ============================================================
// 登录
// ============================================================

func (s *Server) handleLogin(
	addr *net.UDPAddr,
	deviceID string,
	username string,
) {

	client, accepted :=
		s.updateClient(
			addr,
			deviceID,
		)

	if !accepted ||
		client == nil {

		return
	}

	username =
		strings.TrimSpace(
			username,
		)

	if username == "" {
		username =
			client.Username
	}

	if username == "" {
		username =
			fmt.Sprintf(
				"USER-%d",
				addr.Port,
			)
	}

	runes :=
		[]rune(
			username,
		)

	if len(runes) > 20 {

		username =
			string(
				runes[:20],
			)
	}

	username =
		cleanUsername(
			username,
		)

	s.mu.Lock()

	client.DeviceID =
		deviceID

	client.Username =
		username

	client.LastSeen =
		time.Now()

	s.users[client.UserID] =
		client

	s.saveSessionLocked(
		client,
	)

	channelName :=
		client.ChannelName

	userID :=
		client.UserID

	if location, exists :=
		s.memberLocations[userID]; exists {

		location.Username =
			username

		location.ChannelName =
			channelName

		s.memberLocations[userID] =
			location
	}

	s.mu.Unlock()

	response :=
		fmt.Sprintf(
			"%s:%s:%s:%s",
			msgUserOK,
			userID,
			username,
			channelName,
		)

	s.sendMessage(
		addr,
		response,
	)

	s.sendUserStatusToClient(
		addr,
	)

	s.sendUserListToClient(
		addr,
		channelName,
	)

	s.sendMemberLocationsToClient(
		addr,
		channelName,
	)

	s.broadcastChannelMembers(
		channelName,
	)

	s.broadcastChannelList()

	s.broadcastMemberLocations(
		channelName,
	)

	log.Printf(
		"用户登录/昵称更新: user=%s userID=%s channel=%s device=%s",
		username,
		userID,
		channelName,
		deviceID,
	)
}

// ============================================================
// 设置昵称
// ============================================================

func (s *Server) handleSetNickname(
	addr *net.UDPAddr,
	username string,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	username =
		cleanUsername(
			username,
		)

	if username == "" {
		username =
			fmt.Sprintf(
				"USER-%d",
				addr.Port,
			)
	}

	s.mu.Lock()

	client.Username =
		username

	client.LastSeen =
		time.Now()

	s.users[client.UserID] =
		client

	s.saveSessionLocked(
		client,
	)

	channelName :=
		client.ChannelName

	userID :=
		client.UserID

	if location, exists :=
		s.memberLocations[userID]; exists {

		location.Username =
			username

		location.ChannelName =
			channelName

		s.memberLocations[userID] =
			location
	}

	s.mu.Unlock()

	log.Printf(
		"昵称更新: user=%s userID=%s channel=%s device=%s",
		username,
		userID,
		channelName,
		client.DeviceID,
	)

	s.sendMessage(
		addr,
		fmt.Sprintf(
			"%s:%s:%s:%s",
			msgUserOK,
			userID,
			username,
			channelName,
		),
	)

	s.sendUserStatusToClient(
		addr,
	)

	s.broadcastChannelMembers(
		channelName,
	)

	s.broadcastChannelList()

	s.broadcastMemberLocations(
		channelName,
	)
}

// ============================================================
// 创建频道
// ============================================================

func (s *Server) handleCreateChannel(
	addr *net.UDPAddr,
	payload string,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	parts :=
		strings.Split(
			payload,
			":",
		)

	if len(parts) < 1 {

		s.sendMessage(
			addr,
			msgChannelError+":INVALID_NAME",
		)

		return
	}

	channelName :=
		cleanChannelName(
			parts[0],
		)

	if channelName == "" {

		s.sendMessage(
			addr,
			msgChannelError+":INVALID_NAME",
		)

		return
	}

	if channelName ==
		defaultChannel {

		s.sendMessage(
			addr,
			msgChannelError+":RESERVED",
		)

		return
	}

	channelType :=
		channelPublic

	password :=
		""

	if len(parts) >= 2 {

		t :=
			strings.ToUpper(
				strings.TrimSpace(
					parts[1],
				),
			)

		if t ==
			channelPrivate {

			channelType =
				channelPrivate

			if len(parts) >= 3 {

				password =
					strings.TrimSpace(
						parts[2],
					)
			}

			if password == "" {

				s.sendMessage(
					addr,
					msgChannelError+":PASSWORD_REQUIRED",
				)

				return
			}

			if len(
				[]rune(password),
			) > 32 {

				password =
					string(
						[]rune(
							password,
						)[:32],
					)
			}
		}
	}

	s.mu.Lock()

	if _, exists :=
		s.channels[channelName]; exists {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":EXISTS",
		)

		return
	}

	if len(s.channels) >=
		maxChannels {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":LIMIT",
		)

		return
	}

	s.channels[channelName] =
		&Channel{
			Name:        channelName,
			CreatorID:   client.UserID,
			CreatedAt:   time.Now(),
			ChannelType: channelType,
			Password:    password,
		}

	s.mu.Unlock()

	log.Printf(
		"创建频道: name=%s creator=%s type=%s",
		channelName,
		client.Username,
		channelType,
	)

	response :=
		fmt.Sprintf(
			"%s:%s:%s",
			msgChannelCreated,
			channelName,
			channelType,
		)

	s.sendMessage(
		addr,
		response,
	)

	s.broadcastChannelList()
}

// ============================================================
// 删除频道
// ============================================================

func (s *Server) handleDeleteChannel(
	addr *net.UDPAddr,
	channelName string,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	channelName =
		cleanChannelName(
			channelName,
		)

	if channelName == "" {

		s.sendMessage(
			addr,
			msgChannelError+":INVALID_NAME",
		)

		return
	}

	s.mu.Lock()

	channel, exists :=
		s.channels[channelName]

	if !exists {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":NOT_FOUND",
		)

		return
	}

	if channelName ==
		defaultChannel {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":CANNOT_DELETE_PUBLIC",
		)

		return
	}

	if channel.CreatorID !=
		client.UserID {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":NOT_CREATOR",
		)

		return
	}

	delete(
		s.talkers,
		channelName,
	)

	movedClients :=
		make(
			[]*Client,
			0,
		)

	for _, other := range s.clients {

		if other.ChannelName !=
			channelName {

			continue
		}

		other.ChannelName =
			defaultChannel

		other.LastSeen =
			time.Now()

		s.saveSessionLocked(
			other,
		)

		s.moveMemberLocationChannelLocked(
			other.UserID,
			defaultChannel,
		)

		movedClients =
			append(
				movedClients,
				other,
			)
	}

	delete(
		s.channels,
		channelName,
	)

	publicCount :=
		s.channelMemberCountLocked(
			defaultChannel,
		)

	s.mu.Unlock()

	log.Printf(
		"删除频道: channel=%s creator=%s moved=%d",
		channelName,
		client.Username,
		len(movedClients),
	)

	for _, other := range movedClients {

		s.sendMessage(
			other.Addr,
			fmt.Sprintf(
				"%s:%s:%d",
				msgChannelLeft,
				defaultChannel,
				publicCount,
			),
		)

		s.sendUserListToClient(
			other.Addr,
			defaultChannel,
		)

		s.sendMemberLocationsToClient(
			other.Addr,
			defaultChannel,
		)
	}

	s.sendMessage(
		addr,
		msgChannelDeleted+
			":"+
			channelName,
	)

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		defaultChannel,
	)

	s.broadcastMemberLocations(
		defaultChannel,
	)
}

// ============================================================
// 加入频道
// ============================================================

func (s *Server) handleJoinChannel(
	addr *net.UDPAddr,
	payload string,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	parts :=
		strings.SplitN(
			payload,
			":",
			2,
		)

	if len(parts) == 0 {
		return
	}

	channelName :=
		cleanChannelName(
			parts[0],
		)

	password :=
		""

	if len(parts) == 2 {

		password =
			strings.TrimSpace(
				parts[1],
			)
	}

	s.mu.Lock()

	channel, exists :=
		s.channels[channelName]

	if !exists {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":NOT_FOUND",
		)

		return
	}

	if channel.ChannelType ==
		channelPrivate &&
		password !=
			channel.Password {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":BAD_PASSWORD",
		)

		return
	}

	oldChannel :=
		client.ChannelName

	if oldChannel ==
		channelName {

		memberCount :=
			s.channelMemberCountLocked(
				channelName,
			)

		channelType :=
			channel.ChannelType

		s.moveMemberLocationChannelLocked(
			client.UserID,
			channelName,
		)

		s.mu.Unlock()

		s.sendMessage(
			addr,
			fmt.Sprintf(
				"%s:%s:%s:%d",
				msgChannelJoined,
				channelName,
				channelType,
				memberCount,
			),
		)

		s.sendChannelMembers(
			addr,
			channelName,
		)

		s.sendUserListToClient(
			addr,
			channelName,
		)

		s.sendMemberLocationsToClient(
			addr,
			channelName,
		)

		return
	}

	talkReleased :=
		false

	if talker, exists :=
		s.talkers[oldChannel]; exists {

		if talker.UserID ==
			client.UserID {

			delete(
				s.talkers,
				oldChannel,
			)

			talkReleased =
				true
		}
	}

	client.ChannelName =
		channelName

	client.LastSeen =
		time.Now()

	s.saveSessionLocked(
		client,
	)

	s.moveMemberLocationChannelLocked(
		client.UserID,
		channelName,
	)

	memberCount :=
		s.channelMemberCountLocked(
			channelName,
		)

	channelType :=
		channel.ChannelType

	s.mu.Unlock()

	if talkReleased {

		s.broadcastTalkReleased(
			oldChannel,
			client.Username,
		)
	}

	s.sendMessage(
		addr,
		fmt.Sprintf(
			"%s:%s:%s:%d",
			msgChannelJoined,
			channelName,
			channelType,
			memberCount,
		),
	)

	s.sendUserListToClient(
		addr,
		channelName,
	)

	s.sendMemberLocationsToClient(
		addr,
		channelName,
	)

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		oldChannel,
	)

	s.broadcastChannelMembers(
		channelName,
	)

	s.broadcastMemberLocations(
		oldChannel,
	)

	s.broadcastMemberLocations(
		channelName,
	)

	log.Printf(
		"加入频道: user=%s old=%s new=%s type=%s count=%d",
		client.Username,
		oldChannel,
		channelName,
		channelType,
		memberCount,
	)
}

// ============================================================
// 离开频道
// ============================================================

func (s *Server) handleLeaveChannel(
	addr *net.UDPAddr,
) {

	if addr == nil {
		return
	}

	key :=
		addr.String()

	client, exists :=
		func() (*Client, bool) {
			s.mu.RLock()
			defer s.mu.RUnlock()

			c, ok :=
				s.clients[key]

			return c, ok
		}()

	if !exists ||
		client == nil {

		return
	}

	s.mu.Lock()

	client, exists =
		s.clients[key]

	if !exists ||
		client == nil {

		s.mu.Unlock()
		return
	}

	oldChannel :=
		client.ChannelName

	if oldChannel == "" {

		oldChannel =
			defaultChannel
	}

	if oldChannel ==
		defaultChannel {

		client.LastSeen =
			time.Now()

		s.saveSessionLocked(
			client,
		)

		s.moveMemberLocationChannelLocked(
			client.UserID,
			defaultChannel,
		)

		publicCount :=
			s.channelMemberCountLocked(
				defaultChannel,
			)

		s.mu.Unlock()

		s.sendMessage(
			addr,
			fmt.Sprintf(
				"%s:%s:%d",
				msgChannelLeft,
				defaultChannel,
				publicCount,
			),
		)

		s.sendUserListToClient(
			addr,
			defaultChannel,
		)

		s.sendMemberLocationsToClient(
			addr,
			defaultChannel,
		)

		s.broadcastChannelList()

		s.broadcastChannelMembers(
			defaultChannel,
		)

		s.broadcastMemberLocations(
			defaultChannel,
		)

		return
	}

	talkReleased :=
		false

	talkUsername :=
		client.Username

	if talker, exists :=
		s.talkers[oldChannel]; exists {

		if talker.UserID ==
			client.UserID {

			talkUsername =
				talker.Username

			delete(
				s.talkers,
				oldChannel,
			)

			talkReleased =
				true
		}
	}

	client.ChannelName =
		defaultChannel

	client.LastSeen =
		time.Now()

	s.saveSessionLocked(
		client,
	)

	s.moveMemberLocationChannelLocked(
		client.UserID,
		defaultChannel,
	)

	oldChannelCount :=
		s.channelMemberCountLocked(
			oldChannel,
		)

	publicCount :=
		s.channelMemberCountLocked(
			defaultChannel,
		)

	s.mu.Unlock()

	if talkReleased {

		s.broadcastTalkReleased(
			oldChannel,
			talkUsername,
		)
	}

	s.sendMessage(
		addr,
		fmt.Sprintf(
			"%s:%s:%d",
			msgChannelLeft,
			defaultChannel,
			publicCount,
		),
	)

	s.sendUserListToClient(
		addr,
		defaultChannel,
	)

	s.sendMemberLocationsToClient(
		addr,
		defaultChannel,
	)

	s.broadcastChannelList()

	s.broadcastChannelMembers(
		oldChannel,
	)

	s.broadcastChannelMembers(
		defaultChannel,
	)

	s.broadcastMemberLocations(
		oldChannel,
	)

	s.broadcastMemberLocations(
		defaultChannel,
	)

	log.Printf(
		"离开频道: user=%s old=%s new=%s oldCount=%d publicCount=%d",
		client.Username,
		oldChannel,
		defaultChannel,
		oldChannelCount,
		publicCount,
	)
}

// ============================================================
// 频道列表
// ============================================================

func (s *Server) handleChannelList(
	addr *net.UDPAddr,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	s.mu.RLock()

	response :=
		s.buildChannelListLocked()

	s.mu.RUnlock()

	s.sendMessage(
		addr,
		response,
	)
}

// ============================================================
// 广播频道列表
// ============================================================

func (s *Server) broadcastChannelList() {

	s.mu.RLock()

	response :=
		s.buildChannelListLocked()

	addresses :=
		make(
			[]*net.UDPAddr,
			0,
			len(s.clients),
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.Addr == nil {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) <=
			timeout {

			addresses =
				append(
					addresses,
					cloneUDPAddr(
						client.Addr,
					),
				)
		}
	}

	s.mu.RUnlock()

	for _, addr := range addresses {

		s.sendMessage(
			addr,
			response,
		)
	}
}

// ============================================================
// 构造频道列表
// ============================================================

func (s *Server) buildChannelListLocked() string {

	names :=
		make(
			[]string,
			0,
			len(s.channels),
		)

	for name := range s.channels {

		names =
			append(
				names,
				name,
			)
	}

	sortStrings(
		names,
	)

	items :=
		make(
			[]string,
			0,
			len(names),
		)

	for _, name := range names {

		channel :=
			s.channels[name]

		count :=
			s.channelMemberCountLocked(
				name,
			)

		items =
			append(
				items,
				fmt.Sprintf(
					"%s,%s,%d",
					channel.Name,
					channel.ChannelType,
					count,
				),
			)
	}

	return msgChannelList +
		":" +
		strings.Join(
			items,
			";",
		)
}

// ============================================================
// 当前频道信息
// ============================================================

func (s *Server) handleChannelInfo(
	addr *net.UDPAddr,
) {

	s.sendChannelInfoToClient(
		addr,
	)
}

// ============================================================
// 给客户端发送频道信息
// ============================================================

func (s *Server) sendChannelInfoToClient(
	addr *net.UDPAddr,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	s.mu.RLock()

	channel, exists :=
		s.channels[client.ChannelName]

	if !exists {

		s.mu.RUnlock()

		s.sendMessage(
			addr,
			msgChannelError+":NOT_FOUND",
		)

		return
	}

	count :=
		s.channelMemberCountLocked(
			channel.Name,
		)

	response :=
		fmt.Sprintf(
			"%s:%s:%s:%d",
			msgChannelInfo,
			channel.Name,
			channel.ChannelType,
			count,
		)

	channelName :=
		client.ChannelName

	s.mu.RUnlock()

	s.sendMessage(
		addr,
		response,
	)

	s.sendChannelMembers(
		addr,
		channelName,
	)

	s.sendUserListToClient(
		addr,
		channelName,
	)

	s.sendMemberLocationsToClient(
		addr,
		channelName,
	)
}

// ============================================================
// 频道人数
// ============================================================

func (s *Server) channelMemberCountLocked(
	channelName string,
) int {

	if channelName == "" {
		return 0
	}

	users :=
		make(
			map[string]bool,
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		userID :=
			strings.TrimSpace(
				client.UserID,
			)

		if userID == "" {
			continue
		}

		users[userID] = true
	}

	return len(users)
}

// ============================================================
// 频道成员列表
// ============================================================

func (s *Server) buildChannelMembersLocked(
	channelName string,
) string {

	type member struct {
		id       string
		username string
		lastSeen int64
	}

	membersByID :=
		make(
			map[string]member,
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		id :=
			strings.TrimSpace(
				client.UserID,
			)

		if id == "" {
			continue
		}

		username :=
			strings.TrimSpace(
				client.Username,
			)

		if username == "" ||
			strings.HasPrefix(
				username,
				"USER-",
			) {

			continue
		}

		current :=
			member{
				id:       id,
				username: username,
				lastSeen: client.LastSeen.Unix(),
			}

		existing, ok :=
			membersByID[id]

		if !ok ||
			current.lastSeen >
				existing.lastSeen {

			membersByID[id] =
				current
		}
	}

	members :=
		make(
			[]member,
			0,
			len(membersByID),
		)

	for _, m := range membersByID {

		members =
			append(
				members,
				m,
			)
	}

	for i := 0; i < len(members); i++ {

		for j := i + 1; j < len(members); j++ {

			if members[j].username <
				members[i].username {

				members[i],
					members[j] =
					members[j],
					members[i]
			}
		}
	}

	items :=
		make(
			[]string,
			0,
			len(members),
		)

	for _, m := range members {

		items =
			append(
				items,
				fmt.Sprintf(
					"%s,%s,online,%d",
					cleanUsername(
						m.id,
					),
					cleanUsername(
						m.username,
					),
					m.lastSeen,
				),
			)
	}

	return msgChannelMembers +
		":" +
		cleanChannelName(
			channelName,
		) +
		":" +
		strings.Join(
			items,
			";",
		)
}

// ============================================================
// 简单用户列表
// ============================================================

func (s *Server) buildUserListLocked(
	channelName string,
) string {

	type userInfo struct {
		id       string
		username string
		lastSeen int64
	}

	usersByID :=
		make(
			map[string]userInfo,
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		id :=
			cleanUsername(
				client.UserID,
			)

		if id == "" {
			continue
		}

		username :=
			cleanUsername(
				client.Username,
			)

		if username == "" ||
			strings.HasPrefix(
				username,
				"USER-",
			) {

			continue
		}

		current :=
			userInfo{
				id:       id,
				username: username,
				lastSeen: client.LastSeen.Unix(),
			}

		existing, exists :=
			usersByID[id]

		if !exists ||
			current.lastSeen >
				existing.lastSeen {

			usersByID[id] =
				current
		}
	}

	users :=
		make(
			[]userInfo,
			0,
			len(usersByID),
		)

	for _, item := range usersByID {

		users =
			append(
				users,
				item,
			)
	}

	for i := 0; i < len(users); i++ {

		for j := i + 1; j < len(users); j++ {

			if users[j].username <
				users[i].username {

				users[i],
					users[j] =
					users[j],
					users[i]
			}
		}
	}

	items :=
		make(
			[]string,
			0,
			len(users),
		)

	for _, user := range users {

		items =
			append(
				items,
				cleanUsername(
					user.id,
				)+"|"+
					cleanUsername(
						user.username,
					),
			)
	}

	log.Printf(
		"DEBUG USER LIST: channel=%s count=%d users=%s",
		channelName,
		len(items),
		strings.Join(
			items,
			";",
		),
	)

	return msgUserList +
		":" +
		cleanChannelName(
			channelName,
		) +
		":" +
		strings.Join(
			items,
			";",
		)
}

// ============================================================
// 发送用户列表
// ============================================================

func (s *Server) sendUserListToClient(
	addr *net.UDPAddr,
	channelName string,
) {

	if addr == nil ||
		channelName == "" {

		return
	}

	s.mu.RLock()

	response :=
		s.buildUserListLocked(
			channelName,
		)

	s.mu.RUnlock()

	s.sendMessage(
		addr,
		response,
	)
}

// ============================================================
// 广播用户列表
// ============================================================

func (s *Server) broadcastUserList(
	channelName string,
) {

	if channelName == "" {
		return
	}

	s.mu.RLock()

	response :=
		s.buildUserListLocked(
			channelName,
		)

	addresses :=
		make(
			[]*net.UDPAddr,
			0,
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.Addr == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		addresses =
			append(
				addresses,
				cloneUDPAddr(
					client.Addr,
				),
			)
	}

	s.mu.RUnlock()

	for _, addr := range addresses {

		s.sendMessage(
			addr,
			response,
		)
	}
}

// ============================================================
// 发送频道成员
// ============================================================

func (s *Server) sendChannelMembers(
	addr *net.UDPAddr,
	channelName string,
) {

	s.mu.RLock()

	response :=
		s.buildChannelMembersLocked(
			channelName,
		)

	s.mu.RUnlock()

	s.sendMessage(
		addr,
		response,
	)

	s.sendUserListToClient(
		addr,
		channelName,
	)
}

// ============================================================
// 广播频道成员
// ============================================================

func (s *Server) broadcastChannelMembers(
	channelName string,
) {

	if channelName == "" {
		return
	}

	s.mu.RLock()

	response :=
		s.buildChannelMembersLocked(
			channelName,
		)

	addresses :=
		make(
			[]*net.UDPAddr,
			0,
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.Addr == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		addresses =
			append(
				addresses,
				cloneUDPAddr(
					client.Addr,
				),
			)
	}

	s.mu.RUnlock()

	for _, addr := range addresses {

		s.sendMessage(
			addr,
			response,
		)
	}

	s.broadcastUserList(
		channelName,
	)
}

// ============================================================
// 用户状态
// ============================================================

func (s *Server) sendUserStatusToClient(
	addr *net.UDPAddr,
) {

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		return
	}

	s.mu.RLock()

	response :=
		fmt.Sprintf(
			"%s:%s:%s:online:%d:%s",
			msgUserStatus,
			cleanUsername(
				client.UserID,
			),
			cleanUsername(
				client.Username,
			),
			client.LastSeen.Unix(),
			cleanChannelName(
				client.ChannelName,
			),
		)

	s.mu.RUnlock()

	s.sendMessage(
		addr,
		response,
	)
}

// ============================================================
// 清理频道名
// ============================================================

func cleanChannelName(
	name string,
) string {

	name =
		strings.TrimSpace(
			name,
		)

	name =
		strings.ReplaceAll(
			name,
			":",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			"|",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			";",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			",",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			"\n",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			"\r",
			"",
		)

	runes :=
		[]rune(
			name,
		)

	if len(runes) > 24 {

		name =
			string(
				runes[:24],
			)
	}

	return name
}

// ============================================================
// 清理用户名
// ============================================================

func cleanUsername(
	name string,
) string {

	name =
		strings.TrimSpace(
			name,
		)

	name =
		strings.ReplaceAll(
			name,
			":",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			";",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			",",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			"|",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			"\n",
			"",
		)

	name =
		strings.ReplaceAll(
			name,
			"\r",
			"",
		)

	runes :=
		[]rune(
			name,
		)

	if len(runes) > 20 {

		name =
			string(
				runes[:20],
			)
	}

	return name
}

// ============================================================
// 排序
// ============================================================

func sortStrings(
	values []string,
) {

	for i := 0; i < len(values); i++ {

		for j := i + 1; j < len(values); j++ {

			if values[j] <
				values[i] {

				values[i],
					values[j] =
					values[j],
					values[i]
			}
		}
	}
}

// ============================================================
// UDP 地址复制
// ============================================================

func cloneUDPAddr(
	addr *net.UDPAddr,
) *net.UDPAddr {

	if addr == nil {
		return nil
	}

	ip :=
		make(
			net.IP,
			len(addr.IP),
		)

	copy(
		ip,
		addr.IP,
	)

	return &net.UDPAddr{
		IP:   ip,
		Port: addr.Port,
		Zone: addr.Zone,
	}
}

// ============================================================
// 抢麦
// ============================================================

func (s *Server) handleTalkStart(
	addr *net.UDPAddr,
) {

	if addr == nil {
		return
	}

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		/*
		 * 理论上 run() 已经调用过安全迁移。
		 * 这里再兜底一次。
		 */
		if !s.ensureKnownClientForControl(
			addr,
		) {

			return
		}

		client, accepted =
			s.touchKnownClient(
				addr,
			)

		if !accepted ||
			client == nil {

			return
		}
	}

	key :=
		addr.String()

	s.mu.Lock()

	channelName :=
		client.ChannelName

	talker, exists :=
		s.talkers[channelName]

	if exists {

		/*
		 * 当前已经是自己持麦。
		 */
		if talker.UserID ==
			client.UserID {

			talker.Addr =
				cloneUDPAddr(addr)

			talker.IP =
				client.IP

			talker.DeviceID =
				client.DeviceID

			s.mu.Unlock()

			s.sendMessage(
				addr,
				msgTalkOK,
			)

			return
		}

		busyUsername :=
			talker.Username

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgTalkBusy,
		)

		s.sendMessage(
			addr,
			fmt.Sprintf(
				"%s:%s:%s",
				msgTalkBroadcast,
				channelName,
				cleanUsername(
					busyUsername,
				),
			),
		)

		return
	}

	s.talkers[channelName] =
		&TalkState{
			Addr:      cloneUDPAddr(addr),
			UserID:    client.UserID,
			Username:  client.Username,
			IP:        client.IP,
			DeviceID:  client.DeviceID,
			StartTime: time.Now(),
		}

	s.mu.Unlock()

	log.Printf(
		"抢麦成功: user=%s channel=%s addr=%s device=%s",
		client.Username,
		channelName,
		addr.String(),
		client.DeviceID,
	)

	s.sendMessage(
		addr,
		msgTalkOK,
	)

	s.broadcastTalkStart(
		channelName,
		client.Username,
	)

	_ = key
}

// ============================================================
// 广播有人讲话
// ============================================================

func (s *Server) broadcastTalkStart(
	channelName string,
	username string,
) {

	message :=
		fmt.Sprintf(
			"%s:%s:%s",
			msgTalkBroadcast,
			cleanChannelName(
				channelName,
			),
			cleanUsername(
				username,
			),
		)

	s.broadcastToChannel(
		channelName,
		message,
	)
}

// ============================================================
// 广播讲话结束
// ============================================================

func (s *Server) broadcastTalkReleased(
	channelName string,
	username string,
) {

	message :=
		fmt.Sprintf(
			"%s:%s:%s",
			msgTalkRelease,
			cleanChannelName(
				channelName,
			),
			cleanUsername(
				username,
			),
		)

	s.broadcastToChannel(
		channelName,
		message,
	)
}

// ============================================================
// 频道广播
// ============================================================

func (s *Server) broadcastToChannel(
	channelName string,
	message string,
) {

	s.mu.RLock()

	addresses :=
		make(
			[]*net.UDPAddr,
			0,
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.Addr == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		addresses =
			append(
				addresses,
				cloneUDPAddr(
					client.Addr,
				),
			)
	}

	s.mu.RUnlock()

	for _, addr := range addresses {

		s.sendMessage(
			addr,
			message,
		)
	}
}

// ============================================================
// 释放麦权
// ============================================================

func (s *Server) handleTalkStop(
	addr *net.UDPAddr,
) {

	if addr == nil {
		return
	}

	client, accepted :=
		s.touchKnownClient(
			addr,
		)

	if !accepted ||
		client == nil {

		/*
		 * 不再直接 updateClient(addr) 创建匿名 Client。
		 *
		 * 优先尝试安全迁移。
		 */
		if !s.ensureKnownClientForControl(
			addr,
		) {
			return
		}

		client, accepted =
			s.touchKnownClient(
				addr,
			)

		if !accepted ||
			client == nil {

			return
		}
	}

	key :=
		addr.String()

	s.mu.Lock()

	channelName :=
		client.ChannelName

	talker, exists :=
		s.talkers[channelName]

	if !exists {

		s.mu.Unlock()
		return
	}

	/*
	 * 地址不一致时，不再只靠 IP 判断。
	 *
	 * 只要 UserID 相同，则允许这个已经确认身份的
	 * 当前客户端释放自己的麦权。
	 */
	if talker.Addr == nil ||
		talker.Addr.String() != key {

		if talker.UserID ==
			client.UserID {

			talker.Addr =
				cloneUDPAddr(addr)

			talker.IP =
				client.IP

			talker.DeviceID =
				client.DeviceID

			log.Printf(
				"释放麦权时跟随已确认身份UDP端口: new=%s user=%s channel=%s",
				key,
				client.Username,
				channelName,
			)

		} else {

			s.mu.Unlock()
			return
		}
	}

	username :=
		talker.Username

	delete(
		s.talkers,
		channelName,
	)

	s.mu.Unlock()

	log.Printf(
		"讲话结束: user=%s channel=%s",
		username,
		channelName,
	)

	s.sendMessage(
		addr,
		msgTalkRelease,
	)

	s.broadcastTalkReleased(
		channelName,
		username,
	)
}

// ============================================================
// 讲话自动释放
// ============================================================

func (s *Server) talkTimeoutLoop() {

	ticker :=
		time.NewTicker(
			500 * time.Millisecond,
		)

	defer ticker.Stop()

	for range ticker.C {

		type releaseInfo struct {
			channel  string
			addr     *net.UDPAddr
			username string
		}

		releases :=
			make(
				[]releaseInfo,
				0,
			)

		now :=
			time.Now()

		s.mu.Lock()

		for channelName, talker := range s.talkers {

			if talker == nil {
				delete(
					s.talkers,
					channelName,
				)
				continue
			}

			if now.Sub(
				talker.StartTime,
			) < talkTimeout {

				continue
			}

			releases =
				append(
					releases,
					releaseInfo{
						channel: channelName,
						addr: cloneUDPAddr(
							talker.Addr,
						),
						username: talker.Username,
					},
				)

			delete(
				s.talkers,
				channelName,
			)
		}

		s.mu.Unlock()

		for _, release := range releases {

			log.Printf(
				"讲话超过30秒自动释放: channel=%s user=%s",
				release.channel,
				release.username,
			)

			s.sendMessage(
				release.addr,
				msgTalkRelease,
			)

			s.broadcastTalkReleased(
				release.channel,
				release.username,
			)
		}
	}
}

// ============================================================
// 客户端超时清理
// ============================================================

func (s *Server) cleanupLoop() {

	ticker :=
		time.NewTicker(
			5 * time.Second,
		)

	defer ticker.Stop()

	for range ticker.C {

		now :=
			time.Now()

		removedChannels :=
			make(
				map[string]bool,
			)

		releasedUsers :=
			make(
				map[string]string,
			)

		changedLocationChannels :=
			make(
				map[string]bool,
			)

		removedClients :=
			make(
				[]string,
				0,
			)

		s.mu.Lock()

		for key, client := range s.clients {

			if client == nil {

				delete(
					s.clients,
					key,
				)

				continue
			}

			timeout :=
				clientTimeout

			if client.DeviceID == "" {
				timeout =
					anonymousClientTimeout
			}

			if now.Sub(
				client.LastSeen,
			) <= timeout {

				continue
			}

			channelName :=
				client.ChannelName

			if talker, exists :=
				s.talkers[channelName]; exists {

				if talker.UserID ==
					client.UserID ||
					(talker.Addr != nil &&
						talker.Addr.String() == key) {

					delete(
						s.talkers,
						channelName,
					)

					removedChannels[channelName] = true

					releasedUsers[channelName] =
						talker.Username
				}
			}

			if client.UserID != "" {

				s.removeMemberLocationLocked(
					client.UserID,
				)

				changedLocationChannels[channelName] = true
			}

			delete(
				s.clients,
				key,
			)

			removedClients =
				append(
					removedClients,
					key,
				)

			if client.IP != "" &&
				s.ipConnections[client.IP] > 0 {

				s.ipConnections[client.IP]--

				if s.ipConnections[client.IP] <= 0 {

					delete(
						s.ipConnections,
						client.IP,
					)
				}
			}

			if client.UserID != "" {

				if existing, exists :=
					s.users[client.UserID]; exists &&
					existing == client {

					delete(
						s.users,
						client.UserID,
					)
				}
			}

			log.Printf(
				"清理超时客户端: user=%s channel=%s addr=%s device=%s lastSeen=%s timeout=%s",
				client.Username,
				channelName,
				key,
				client.DeviceID,
				client.LastSeen.Format(
					time.RFC3339,
				),
				timeout,
			)
		}

		for id, session := range s.sessions {

			if session == nil ||
				now.Sub(
					session.LastSeen,
				) > 10*clientTimeout {

				delete(
					s.sessions,
					id,
				)
			}
		}

		s.mu.Unlock()

		_ = removedClients

		s.broadcastChannelList()

		for channelName := range removedChannels {

			username :=
				releasedUsers[channelName]

			if username == "" {
				username = "system"
			}

			s.broadcastTalkReleased(
				channelName,
				username,
			)

			s.broadcastChannelMembers(
				channelName,
			)

			s.broadcastMemberLocations(
				channelName,
			)
		}

		for channelName := range changedLocationChannels {

			s.broadcastMemberLocations(
				channelName,
			)
		}
	}
}

// ============================================================
// 成员位置：解析时间戳
// ============================================================

func parseLocationTimestamp(
	value string,
) int64 {

	value =
		strings.TrimSpace(
			value,
		)

	if value == "" {
		return time.Now().Unix()
	}

	ts, err :=
		strconv.ParseInt(
			value,
			10,
			64,
		)

	if err != nil {
		return 0
	}

	if ts > 100000000000 {
		ts /= 1000
	}

	if ts <= 0 {
		return 0
	}

	return ts
}

// ============================================================
// 成员位置：处理手机位置
// ============================================================

func (s *Server) handleLocationUpdate(
	addr *net.UDPAddr,
	text string,
) {

	if addr == nil {
		return
	}

	if !strings.HasPrefix(
		text,
		msgLocation+":",
	) {
		return
	}

	payload :=
		strings.TrimPrefix(
			text,
			msgLocation+":",
		)

	parts :=
		strings.SplitN(
			payload,
			":",
			3,
		)

	if len(parts) < 2 {

		log.Printf(
			"GPS位置格式错误: addr=%s text=%q",
			addr.String(),
			text,
		)

		return
	}

	latitude, err :=
		strconv.ParseFloat(
			strings.TrimSpace(parts[0]),
			64,
		)

	if err != nil {

		log.Printf(
			"GPS纬度解析失败: addr=%s value=%q",
			addr.String(),
			parts[0],
		)

		return
	}

	longitude, err :=
		strconv.ParseFloat(
			strings.TrimSpace(parts[1]),
			64,
		)

	if err != nil {

		log.Printf(
			"GPS经度解析失败: addr=%s value=%q",
			addr.String(),
			parts[1],
		)

		return
	}

	if math.IsNaN(latitude) ||
		math.IsInf(latitude, 0) ||
		latitude < -90 ||
		latitude > 90 {

		log.Printf(
			"GPS纬度非法: addr=%s latitude=%v",
			addr.String(),
			latitude,
		)

		return
	}

	if math.IsNaN(longitude) ||
		math.IsInf(longitude, 0) ||
		longitude < -180 ||
		longitude > 180 {

		log.Printf(
			"GPS经度非法: addr=%s longitude=%v",
			addr.String(),
			longitude,
		)

		return
	}

	timestamp :=
		time.Now().Unix()

	if len(parts) >= 3 {

		if parsed :=
			parseLocationTimestamp(
				parts[2],
			); parsed > 0 {

			timestamp =
				parsed
		}
	}

	key :=
		addr.String()

	now :=
		time.Now()

	s.mu.Lock()

	client, exists :=
		s.clients[key]

	if !exists ||
		client == nil {

		s.mu.Unlock()

		log.Printf(
			"忽略未知地址GPS: addr=%s",
			key,
		)

		return
	}

	if client.UserID == "" {

		s.mu.Unlock()

		log.Printf(
			"忽略无UserID GPS: addr=%s",
			key,
		)

		return
	}

	client.LastSeen =
		now

	username :=
		cleanUsername(
			client.Username,
		)

	if username == "" {
		username =
			fmt.Sprintf(
				"USER-%d",
				addr.Port,
			)
	}

	channelName :=
		client.ChannelName

	s.saveSessionLocked(
		client,
	)

	s.memberLocations[client.UserID] =
		MemberLocation{
			UserID:      client.UserID,
			Username:    username,
			ChannelName: channelName,
			Latitude:    latitude,
			Longitude:   longitude,
			Timestamp:   timestamp,
			UpdatedAt:   now,
		}

	s.mu.Unlock()

	log.Printf(
		"GPS位置更新: user=%s userID=%s channel=%s lat=%.6f lon=%.6f timestamp=%d",
		username,
		client.UserID,
		channelName,
		latitude,
		longitude,
		timestamp,
	)

	s.broadcastMemberLocations(
		channelName,
	)
}

// ============================================================
// 成员位置：删除
// ============================================================

func (s *Server) removeMemberLocationLocked(
	userID string,
) {

	userID =
		strings.TrimSpace(
			userID,
		)

	if userID == "" {
		return
	}

	delete(
		s.memberLocations,
		userID,
	)
}

// ============================================================
// 成员位置：移动频道
// ============================================================

func (s *Server) moveMemberLocationChannelLocked(
	userID string,
	channelName string,
) {

	userID =
		strings.TrimSpace(
			userID,
		)

	channelName =
		cleanChannelName(
			channelName,
		)

	if userID == "" ||
		channelName == "" {

		return
	}

	location, exists :=
		s.memberLocations[userID]

	if !exists {
		return
	}

	location.ChannelName =
		channelName

	s.memberLocations[userID] =
		location
}

// ============================================================
// 成员位置：构造单条位置消息
// ============================================================

func buildMemberLocationMessage(
	location MemberLocation,
) string {

	return fmt.Sprintf(
		"%s:%s:%s:%s:%.6f:%.6f:%d",
		msgMemberLocation,
		cleanChannelName(
			location.ChannelName,
		),
		cleanUsername(
			location.UserID,
		),
		cleanUsername(
			location.Username,
		),
		location.Latitude,
		location.Longitude,
		location.Timestamp,
	)
}

// ============================================================
// 成员位置：发送给单个客户端
// ============================================================

func (s *Server) sendMemberLocationsToClient(
	addr *net.UDPAddr,
	channelName string,
) {

	if addr == nil ||
		channelName == "" {

		return
	}

	now :=
		time.Now()

	messages :=
		make(
			[]string,
			0,
		)

	s.mu.Lock()

	for userID, location := range s.memberLocations {

		if location.ChannelName !=
			channelName {

			continue
		}

		if now.Sub(
			location.UpdatedAt,
		) > locationStaleAfter {

			delete(
				s.memberLocations,
				userID,
			)

			continue
		}

		messages =
			append(
				messages,
				buildMemberLocationMessage(
					location,
				),
			)
	}

	s.mu.Unlock()

	for _, message := range messages {

		s.sendMessage(
			addr,
			message,
		)
	}
}

// ============================================================
// 成员位置：广播当前频道
// ============================================================

func (s *Server) broadcastMemberLocations(
	channelName string,
) {

	if channelName == "" {
		return
	}

	type broadcastItem struct {
		location MemberLocation
	}

	now :=
		time.Now()

	locations :=
		make(
			[]broadcastItem,
			0,
		)

	addresses :=
		make(
			[]*net.UDPAddr,
			0,
		)

	addressSeen :=
		make(
			map[string]bool,
		)

	s.mu.Lock()

	for userID, location := range s.memberLocations {

		if now.Sub(
			location.UpdatedAt,
		) > locationStaleAfter {

			delete(
				s.memberLocations,
				userID,
			)

			continue
		}

		if location.ChannelName !=
			channelName {

			continue
		}

		client, online :=
			s.users[location.UserID]

		if !online ||
			client == nil ||
			client.Addr == nil {

			continue
		}

		if client.ChannelName !=
			channelName {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		location.Username =
			cleanUsername(
				client.Username,
			)

		s.memberLocations[userID] =
			location

		locations =
			append(
				locations,
				broadcastItem{
					location: location,
				},
			)
	}

	for _, client := range s.clients {

		if client == nil ||
			client.Addr == nil ||
			client.ChannelName !=
				channelName {

			continue
		}

		if client.UserID == "" {
			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		key :=
			client.Addr.String()

		if addressSeen[key] {
			continue
		}

		addressSeen[key] =
			true

		addresses =
			append(
				addresses,
				cloneUDPAddr(
					client.Addr,
				),
			)
	}

	s.mu.Unlock()

	if len(locations) == 0 ||
		len(addresses) == 0 {

		return
	}

	for _, item := range locations {

		message :=
			buildMemberLocationMessage(
				item.location,
			)

		for _, addr := range addresses {

			s.sendMessage(
				addr,
				message,
			)
		}
	}
}

// ============================================================
// 成员位置：定时广播
// ============================================================

func (s *Server) locationBroadcastLoop() {

	ticker :=
		time.NewTicker(
			locationBroadcastInterval,
		)

	defer ticker.Stop()

	for range ticker.C {

		channels :=
			make(
				map[string]bool,
			)

		now :=
			time.Now()

		s.mu.RLock()

		for _, client := range s.clients {

			if client == nil ||
				client.ChannelName == "" ||
				client.UserID == "" {

				continue
			}

			timeout :=
				clientTimeout

			if client.DeviceID == "" {
				timeout =
					anonymousClientTimeout
			}

			if now.Sub(
				client.LastSeen,
			) > timeout {

				continue
			}

			channels[client.ChannelName] = true
		}

		s.mu.RUnlock()

		for channelName := range channels {

			s.broadcastMemberLocations(
				channelName,
			)
		}
	}
}

// ============================================================
// 音频统计：收到
// ============================================================

func (s *Server) recordReceived(
	bytes uint64,
) {

	s.mu.Lock()

	s.stats.ReceivedPackets++
	s.stats.ReceivedBytes += bytes

	s.mu.Unlock()
}

// ============================================================
// 音频统计：丢弃
// ============================================================

func (s *Server) recordDrop(
	bytes uint64,
	invalid bool,
) {

	s.mu.Lock()

	s.stats.DroppedPackets++
	s.stats.DroppedBytes += bytes

	if invalid {
		s.stats.InvalidPackets++
	}

	s.mu.Unlock()
}

// ============================================================
// 音频统计：转发
// ============================================================

func (s *Server) recordForward(
	bytes uint64,
) {

	s.mu.Lock()

	s.stats.ForwardedPackets++
	s.stats.ForwardedBytes += bytes

	s.mu.Unlock()
}

// ============================================================
// 统计
// ============================================================

func (s *Server) statsLoop() {

	ticker :=
		time.NewTicker(
			30 * time.Second,
		)

	defer ticker.Stop()

	for range ticker.C {

		s.mu.Lock()

		receivedPackets :=
			s.stats.ReceivedPackets

		receivedBytes :=
			s.stats.ReceivedBytes

		forwardedPackets :=
			s.stats.ForwardedPackets

		forwardedBytes :=
			s.stats.ForwardedBytes

		droppedPackets :=
			s.stats.DroppedPackets

		droppedBytes :=
			s.stats.DroppedBytes

		invalidPackets :=
			s.stats.InvalidPackets

		online :=
			len(s.clients)

		onlineDeviceIDs :=
			make(
				map[string]struct{},
			)

		now :=
			time.Now()

		for _, client := range s.clients {

			if client == nil ||
				client.DeviceID == "" ||
				client.Addr == nil {

				continue
			}

			if now.Sub(
				client.LastSeen,
			) > clientTimeout {

				continue
			}

			onlineDeviceIDs[client.DeviceID] = struct{}{}
		}

		userCount :=
			len(onlineDeviceIDs)

		channelCount :=
			len(s.channels)

		talkCount :=
			len(s.talkers)

		locationCount :=
			0

		for _, location := range s.memberLocations {

			if now.Sub(
				location.UpdatedAt,
			) <= locationStaleAfter {

				locationCount++
			}
		}

		s.mu.Unlock()

		log.Printf(
			"音频统计: 收包=%d/%dB 转发=%d/%dB 丢弃=%d/%dB 异常=%d 在线连接=%d 在线用户=%d 频道=%d 抢麦=%d GPS位置=%d",
			receivedPackets,
			receivedBytes,
			forwardedPackets,
			forwardedBytes,
			droppedPackets,
			droppedBytes,
			invalidPackets,
			online,
			userCount,
			channelCount,
			talkCount,
			locationCount,
		)
	}
}

// ============================================================
// 音频转发
// ============================================================

func (s *Server) relayAudio(
	data []byte,
	sender *net.UDPAddr,
) {

	if sender == nil {
		return
	}

	if len(data) <= 0 ||
		len(data) > maxPacketSize {

		s.recordDrop(
			uint64(len(data)),
			true,
		)

		return
	}

	senderKey :=
		sender.String()

	s.mu.RLock()

	senderClient,
		exists :=
		s.clients[senderKey]

	s.mu.RUnlock()

	if !exists ||
		senderClient == nil {

		if !s.migrateTalkerByAddress(
			sender,
		) {

			s.recordDrop(
				uint64(len(data)),
				true,
			)

			return
		}

		s.mu.RLock()

		senderClient =
			s.clients[senderKey]

		s.mu.RUnlock()

		if senderClient == nil {

			s.recordDrop(
				uint64(len(data)),
				true,
			)

			return
		}
	}

	s.mu.RLock()

	channelName :=
		senderClient.ChannelName

	talker :=
		s.talkers[channelName]

	validTalker :=
		false

	if talker != nil {

		/*
		 * 正常情况：
		 * 当前 UDP 地址就是麦权地址。
		 */
		if talker.Addr != nil &&
			talker.Addr.String() ==
				senderKey {

			validTalker =
				true
		}

		/*
		 * 已确认 UserID：
		 *
		 * 允许当前 Client 地址和 talker 地址
		 * 在极短时间内存在差异。
		 */
		if !validTalker &&
			talker.UserID != "" &&
			talker.UserID ==
				senderClient.UserID {

			validTalker =
				true
		}
	}

	s.mu.RUnlock()

	if !validTalker {

		/*
		 * 最后一层：
		 *
		 * 只有“同公网 IP 唯一客户端”
		 * 才允许持麦迁移。
		 */
		if s.migrateTalkerByAddress(
			sender,
		) {

			s.mu.RLock()

			senderClient =
				s.clients[senderKey]

			if senderClient != nil {

				channelName =
					senderClient.ChannelName
			}

			talker =
				s.talkers[channelName]

			validTalker =
				talker != nil &&
					talker.UserID ==
						senderClient.UserID &&
					talker.Addr != nil &&
					talker.Addr.String() ==
						senderKey

			s.mu.RUnlock()
		}
	}

	if !validTalker {

		s.recordDrop(
			uint64(len(data)),
			false,
		)

		return
	}

	s.mu.Lock()

	senderClient =
		s.clients[senderKey]

	if senderClient == nil {

		s.mu.Unlock()

		s.recordDrop(
			uint64(len(data)),
			true,
		)

		return
	}

	channelName =
		senderClient.ChannelName

	talker =
		s.talkers[channelName]

	if talker == nil {

		s.mu.Unlock()

		s.recordDrop(
			uint64(len(data)),
			false,
		)

		return
	}

	/*
	 * 最终确认：
	 *
	 * UserID 必须与当前麦权一致。
	 */
	if talker.UserID !=
		senderClient.UserID {

		s.mu.Unlock()

		s.recordDrop(
			uint64(len(data)),
			false,
		)

		return
	}

	if talker.Addr == nil ||
		talker.Addr.String() !=
			senderKey {

		talker.Addr =
			cloneUDPAddr(
				sender,
			)

		talker.IP =
			senderClient.IP

		talker.DeviceID =
			senderClient.DeviceID
	}

	senderClient.LastSeen =
		time.Now()

	s.saveSessionLocked(
		senderClient,
	)

	clients :=
		make(
			[]*Client,
			0,
			len(s.clients),
		)

	now :=
		time.Now()

	for _, client := range s.clients {

		if client == nil ||
			client.Addr == nil {

			continue
		}

		timeout :=
			clientTimeout

		if client.DeviceID == "" {
			timeout =
				anonymousClientTimeout
		}

		if now.Sub(
			client.LastSeen,
		) > timeout {

			continue
		}

		if client.ChannelName !=
			channelName {

			continue
		}

		if client.Addr.String() ==
			senderKey {

			continue
		}

		clients =
			append(
				clients,
				client,
			)
	}

	s.mu.Unlock()

	for _, client := range clients {

		if client == nil ||
			client.Addr == nil {

			continue
		}

		if _, err :=
			s.conn.WriteToUDP(
				data,
				client.Addr,
			); err != nil {

			log.Printf(
				"音频转发失败: %s channel=%s error=%v",
				client.Addr.String(),
				channelName,
				err,
			)

			continue
		}

		s.recordForward(
			uint64(len(data)),
		)
	}
}
