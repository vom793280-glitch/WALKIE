package main

import (
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	// ============================================================
	// WALKIE V11
	// Android / VPS 版本统一：V11
	// ============================================================

	serverVersion = "WALKIE V11"

	listenAddr    = ":50000"
	maxClients    = 32
	maxChannels   = 32
	maxUsers      = 128
	clientTimeout = 60 * time.Second
	talkTimeout   = 30 * time.Second
	maxPacketSize = 1500

	defaultChannel = "public"

	// ============================================================
	// 基础协议
	// ============================================================

	msgHello     = "WALKIE_HELLO"
	msgConnected = "WALKIE_CONNECTED"
	msgKeepAlive = "WALKIE_KEEPALIVE"
	msgGoodbye   = "WALKIE_GOODBYE"

	// ============================================================
	// 抢麦协议
	// ============================================================

	msgTalkStart   = "WALKIE_TALK_START"
	msgTalkStop    = "WALKIE_TALK_STOP"
	msgTalkOK      = "WALKIE_TALK_OK"
	msgTalkBusy    = "WALKIE_TALK_BUSY"
	msgTalkRelease = "WALKIE_TALK_RELEASED"

	// ============================================================
	// 用户
	// ============================================================

	msgUserOK = "WALKIE_USER_OK"

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

	channelPublic  = "PUBLIC"
	channelPrivate = "PRIVATE"
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
// 抢麦状态
// ============================================================

type TalkState struct {
	Addr      *net.UDPAddr
	StartTime time.Time
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
}

// ============================================================
// main
// ============================================================

func main() {

	log.SetFlags(
		log.Ldate |
			log.Ltime |
			log.Lmicroseconds,
	)

	addr, err := net.ResolveUDPAddr(
		"udp",
		listenAddr,
	)
	if err != nil {
		log.Fatal(
			"解析 UDP 地址失败:",
			err,
		)
	}

	conn, err := net.ListenUDP(
		"udp",
		addr,
	)
	if err != nil {
		log.Fatal(
			"监听 UDP 50000 失败:",
			err,
		)
	}

	defer conn.Close()

	server := &Server{
		conn: conn,

		clients: make(
			map[string]*Client,
		),

		users: make(
			map[string]*Client,
		),

		channels: make(
			map[string]*Channel,
		),

		talkers: make(
			map[string]*TalkState,
		),
	}

	// ============================================================
	// 默认公共频道
	// ============================================================

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
	log.Println("讲话超时:", talkTimeout)
	log.Println("默认频道:", defaultChannel)
	log.Println("公开频道: 已启用")
	log.Println("私密频道: 已启用")
	log.Println("频道密码: 已启用")
	log.Println("在线人数: 已启用")
	log.Println("删除频道: 已启用")
	log.Println("创建者权限: 已启用")
	log.Println("频道隔离: 已启用")
	log.Println("抢麦功能: 已启用")
	log.Println("长按讲话: 已启用")
	log.Println("服务器已启动，等待手机连接...")
	log.Println("========================================")

	go server.talkTimeoutLoop()
	go server.cleanupLoop()

	server.run()
}

// ============================================================
// UDP 主循环
// ============================================================

func (s *Server) run() {

	buffer := make([]byte, maxPacketSize)

	for {

		n, remoteAddr, err := s.conn.ReadFromUDP(buffer)

		if err != nil {
			log.Println(
				"读取 UDP 数据失败:",
				err,
			)
			continue
		}

		if n <= 0 {
			continue
		}

		data := make([]byte, n)
		copy(data, buffer[:n])

		text := string(data)

		// ========================================================
		// HELLO
		// ========================================================

		if text == msgHello {

			client := s.updateClient(remoteAddr)

			log.Printf(
				"收到连接请求: %s user=%s channel=%s",
				remoteAddr.String(),
				client.Username,
				client.ChannelName,
			)

			s.sendMessage(
				remoteAddr,
				msgConnected,
			)

			continue
		}

		// ========================================================
		// KEEP ALIVE
		// ========================================================

		if text == msgKeepAlive {

			client := s.updateClient(remoteAddr)

			log.Printf(
				"收到心跳: %s user=%s channel=%s",
				remoteAddr.String(),
				client.Username,
				client.ChannelName,
			)

			continue
		}

		// ========================================================
		// GOODBYE
		// ========================================================

		if text == msgGoodbye {

			s.removeClient(remoteAddr)

			log.Printf(
				"客户端断开: %s",
				remoteAddr.String(),
			)

			continue
		}

		// ========================================================
		// 登录
		// ========================================================

		if strings.HasPrefix(
			text,
			"WALKIE_LOGIN:",
		) {

			username := strings.TrimSpace(
				strings.TrimPrefix(
					text,
					"WALKIE_LOGIN:",
				),
			)

			s.handleLogin(
				remoteAddr,
				username,
			)

			continue
		}

		// ========================================================
		// 获取频道列表
		// ========================================================

		if text == "WALKIE_CHANNEL_LIST" {

			s.handleChannelList(
				remoteAddr,
			)

			continue
		}

		// ========================================================
		// 创建频道
		//
		// 公开：
		// WALKIE_CREATE_CHANNEL:频道名:PUBLIC
		//
		// 私密：
		// WALKIE_CREATE_CHANNEL:频道名:PRIVATE:密码
		// ========================================================

		if strings.HasPrefix(
			text,
			"WALKIE_CREATE_CHANNEL:",
		) {

			payload := strings.TrimPrefix(
				text,
				"WALKIE_CREATE_CHANNEL:",
			)

			s.handleCreateChannel(
				remoteAddr,
				payload,
			)

			continue
		}

		// ========================================================
		// 加入频道
		//
		// 公开：
		// WALKIE_JOIN_CHANNEL:频道名
		//
		// 私密：
		// WALKIE_JOIN_CHANNEL:频道名:密码
		// ========================================================

		if strings.HasPrefix(
			text,
			"WALKIE_JOIN_CHANNEL:",
		) {

			payload := strings.TrimPrefix(
				text,
				"WALKIE_JOIN_CHANNEL:",
			)

			s.handleJoinChannel(
				remoteAddr,
				payload,
			)

			continue
		}

		// ========================================================
		// 删除频道
		//
		// WALKIE_DELETE_CHANNEL:频道名
		// ========================================================

		if strings.HasPrefix(
			text,
			"WALKIE_DELETE_CHANNEL:",
		) {

			channelName := strings.TrimPrefix(
				text,
				"WALKIE_DELETE_CHANNEL:",
			)

			s.handleDeleteChannel(
				remoteAddr,
				channelName,
			)

			continue
		}

		// ========================================================
		// 离开频道
		// ========================================================

		if text == "WALKIE_LEAVE_CHANNEL" {

			s.handleLeaveChannel(
				remoteAddr,
			)

			continue
		}

		// ========================================================
		// 当前频道信息
		// ========================================================

		if text == "WALKIE_CHANNEL_INFO" {

			s.handleChannelInfo(
				remoteAddr,
			)

			continue
		}

		// ========================================================
		// 抢麦
		// ========================================================

		if text == msgTalkStart {

			client := s.updateClient(remoteAddr)

			log.Printf(
				"收到抢麦请求: %s user=%s channel=%s",
				remoteAddr.String(),
				client.Username,
				client.ChannelName,
			)

			s.handleTalkStart(
				remoteAddr,
			)

			continue
		}

		// ========================================================
		// 释放麦权
		// ========================================================

		if text == msgTalkStop {

			client := s.updateClient(remoteAddr)

			log.Printf(
				"收到停止讲话: %s user=%s channel=%s",
				remoteAddr.String(),
				client.Username,
				client.ChannelName,
			)

			s.handleTalkStop(
				remoteAddr,
			)

			continue
		}

		// ========================================================
		// 音频
		// ========================================================

		client := s.updateClient(remoteAddr)

		log.Printf(
			"收到 UDP 音频: %d 字节，来自 %s user=%s channel=%s",
			n,
			remoteAddr.String(),
			client.Username,
			client.ChannelName,
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

	data := []byte(message)

	_, err := s.conn.WriteToUDP(
		data,
		addr,
	)

	if err != nil {

		log.Printf(
			"发送消息失败: %s -> %v",
			addr.String(),
			err,
		)

		return
	}

	log.Printf(
		"发送消息: %s -> %s",
		message,
		addr.String(),
	)
}

// ============================================================
// 更新 / 创建客户端
// ============================================================

func (s *Server) updateClient(
	addr *net.UDPAddr,
) *Client {

	key := addr.String()

	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()

	if client, exists := s.clients[key]; exists {

		client.LastSeen = now

		return client
	}

	if len(s.clients) >= maxClients {

		log.Println(
			"客户端数量达到上限:",
			key,
		)

		return &Client{
			Addr:        addr,
			UserID:      "LIMITED",
			Username:    "LIMITED",
			ChannelName: defaultChannel,
			LastSeen:    now,
		}
	}

	userID := fmt.Sprintf(
		"U-%s-%d",
		normalizeIP(addr.IP.String()),
		addr.Port,
	)

	username := fmt.Sprintf(
		"USER-%d",
		addr.Port,
	)

	client := &Client{
		Addr:        addr,
		LastSeen:    now,
		UserID:      userID,
		Username:    username,
		ChannelName: defaultChannel,
	}

	s.clients[key] = client

	if len(s.users) < maxUsers {
		s.users[userID] = client
	}

	log.Println(
		"新客户端:",
		key,
		"userID=",
		userID,
		"username=",
		username,
		"channel=",
		defaultChannel,
	)

	return client
}

// ============================================================
// IP
// ============================================================

func normalizeIP(
	ip string,
) string {

	ip = strings.ReplaceAll(
		ip,
		":",
		"_",
	)

	ip = strings.ReplaceAll(
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
) {

	key := addr.String()

	s.mu.Lock()
	defer s.mu.Unlock()

	client, exists := s.clients[key]

	if !exists {
		return
	}

	channelName := client.ChannelName

	if talker, exists := s.talkers[channelName]; exists {

		if talker.Addr.String() == key {

			delete(
				s.talkers,
				channelName,
			)
		}
	}

	delete(
		s.clients,
		key,
	)

	if client.UserID != "" {

		delete(
			s.users,
			client.UserID,
		)
	}

	log.Printf(
		"客户端已删除: user=%s channel=%s",
		client.Username,
		channelName,
	)
}

// ============================================================
// 登录
// ============================================================

func (s *Server) handleLogin(
	addr *net.UDPAddr,
	username string,
) {

	client := s.updateClient(addr)

	username = strings.TrimSpace(username)

	if username == "" {

		username = fmt.Sprintf(
			"USER-%d",
			addr.Port,
		)
	}

	runes := []rune(username)

	if len(runes) > 20 {

		username = string(
			runes[:20],
		)
	}

	s.mu.Lock()

	client.Username = username

	s.mu.Unlock()

	response := fmt.Sprintf(
		"%s:%s:%s:%s",
		msgUserOK,
		client.UserID,
		client.Username,
		client.ChannelName,
	)

	s.sendMessage(
		addr,
		response,
	)
}

// ============================================================
// 创建频道
// ============================================================

func (s *Server) handleCreateChannel(
	addr *net.UDPAddr,
	payload string,
) {

	client := s.updateClient(addr)

	parts := strings.Split(
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

	channelName := cleanChannelName(
		parts[0],
	)

	if channelName == "" {

		s.sendMessage(
			addr,
			msgChannelError+":INVALID_NAME",
		)

		return
	}

	channelType := channelPublic

	password := ""

	if len(parts) >= 2 {

		t := strings.ToUpper(
			strings.TrimSpace(
				parts[1],
			),
		)

		if t == channelPrivate {

			channelType = channelPrivate

			if len(parts) >= 3 {

				password = strings.TrimSpace(
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

			if len([]rune(password)) > 32 {

				password = string(
					[]rune(password)[:32],
				)
			}
		}
	}

	s.mu.Lock()

	if _, exists := s.channels[channelName]; exists {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":EXISTS",
		)

		return
	}

	if len(s.channels) >= maxChannels {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":LIMIT",
		)

		return
	}

	s.channels[channelName] = &Channel{
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

	response := fmt.Sprintf(
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
//
// 权限：只有创建者可以删除
//
// public：不能删除
//
// 删除后：
// 1. 删除频道
// 2. 释放麦权
// 3. 所有频道成员进入 public
// 4. 通知这些用户
// 5. 全员刷新频道列表
// ============================================================

func (s *Server) handleDeleteChannel(
	addr *net.UDPAddr,
	channelName string,
) {

	client := s.updateClient(addr)

	channelName = cleanChannelName(
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

	if channelName == defaultChannel {

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgChannelError+":CANNOT_DELETE_PUBLIC",
		)

		return
	}

	if channel.CreatorID != client.UserID {

		s.mu.Unlock()

		log.Printf(
			"删除频道失败：无权限 user=%s channel=%s",
			client.Username,
			channelName,
		)

		s.sendMessage(
			addr,
			msgChannelError+":NOT_CREATOR",
		)

		return
	}

	// ------------------------------------------------------------
	// 删除抢麦状态
	// ------------------------------------------------------------

	delete(
		s.talkers,
		channelName,
	)

	// ------------------------------------------------------------
	// 收集频道成员
	// 同时移动到 public
	// ------------------------------------------------------------

	movedClients := make(
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

		movedClients = append(
			movedClients,
			other,
		)
	}

	// ------------------------------------------------------------
	// 删除频道
	// ------------------------------------------------------------

	delete(
		s.channels,
		channelName,
	)

	publicCount :=
		s.channelMemberCountLocked(
			defaultChannel,
		)

	s.mu.Unlock()

	log.Println(
		"========================================",
	)

	log.Printf(
		"删除频道成功: channel=%s creator=%s",
		channelName,
		client.Username,
	)

	log.Printf(
		"移动用户数量: %d",
		len(movedClients),
	)

	log.Println(
		"========================================",
	)

	// ------------------------------------------------------------
	// 通知频道成员回到 public
	// ------------------------------------------------------------

	for _, other := range movedClients {

		response := fmt.Sprintf(
			"%s:%s:%d",
			msgChannelLeft,
			defaultChannel,
			publicCount,
		)

		s.sendMessage(
			other.Addr,
			response,
		)
	}

	// ------------------------------------------------------------
	// 通知删除者删除成功
	// ------------------------------------------------------------

	s.sendMessage(
		addr,
		msgChannelDeleted+":"+channelName,
	)

	// ------------------------------------------------------------
	// 所有客户端刷新频道列表
	// ------------------------------------------------------------

	s.broadcastChannelList()
}

// ============================================================
// 加入频道
// ============================================================

func (s *Server) handleJoinChannel(
	addr *net.UDPAddr,
	payload string,
) {

	client := s.updateClient(addr)

	parts := strings.SplitN(
		payload,
		":",
		2,
	)

	channelName := cleanChannelName(
		parts[0],
	)

	password := ""

	if len(parts) == 2 {

		password = strings.TrimSpace(
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
		channelPrivate {

		if password !=
			channel.Password {

			s.mu.Unlock()

			log.Printf(
				"私密频道密码错误: user=%s channel=%s",
				client.Username,
				channelName,
			)

			s.sendMessage(
				addr,
				msgChannelError+":BAD_PASSWORD",
			)

			return
		}
	}

	oldChannel :=
		client.ChannelName

	// ------------------------------------------------------------
	// 换频道时释放旧频道麦权
	// ------------------------------------------------------------

	if talker, exists :=
		s.talkers[oldChannel]; exists {

		if talker.Addr.String() ==
			addr.String() {

			delete(
				s.talkers,
				oldChannel,
			)
		}
	}

	client.ChannelName =
		channelName

	client.LastSeen =
		time.Now()

	memberCount :=
		s.channelMemberCountLocked(
			channelName,
		)

	channelType :=
		channel.ChannelType

	s.mu.Unlock()

	response := fmt.Sprintf(
		"%s:%s:%s:%d",
		msgChannelJoined,
		channelName,
		channelType,
		memberCount,
	)

	s.sendMessage(
		addr,
		response,
	)

	s.broadcastChannelList()

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

	client := s.updateClient(addr)

	s.mu.Lock()

	oldChannel :=
		client.ChannelName

	if talker, exists :=
		s.talkers[oldChannel]; exists {

		if talker.Addr.String() ==
			addr.String() {

			delete(
				s.talkers,
				oldChannel,
			)
		}
	}

	client.ChannelName =
		defaultChannel

	client.LastSeen =
		time.Now()

	memberCount :=
		s.channelMemberCountLocked(
			defaultChannel,
		)

	s.mu.Unlock()

	response := fmt.Sprintf(
		"%s:%s:%d",
		msgChannelLeft,
		defaultChannel,
		memberCount,
	)

	s.sendMessage(
		addr,
		response,
	)

	s.broadcastChannelList()
}

// ============================================================
// 频道列表
// ============================================================

func (s *Server) handleChannelList(
	addr *net.UDPAddr,
) {

	s.updateClient(addr)

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

	now := time.Now()

	for _, client :=
		range s.clients {

		if now.Sub(
			client.LastSeen,
		) <= clientTimeout {

			addresses =
				append(
					addresses,
					client.Addr,
				)
		}
	}

	s.mu.RUnlock()

	for _, addr :=
		range addresses {

		s.sendMessage(
			addr,
			response,
		)
	}
}

// ============================================================
// 构造频道列表
//
// 返回：
//
// WALKIE_CHANNEL_LIST:
// public,PUBLIC,2;
// 测试,PUBLIC,3;
// 私密,PRIVATE,1
//
// 不发送密码
// ============================================================

func (s *Server) buildChannelListLocked() string {

	names :=
		make(
			[]string,
			0,
			len(s.channels),
		)

	for name :=
		range s.channels {

		names =
			append(
				names,
				name,
			)
	}

	sortStrings(names)

	items :=
		make(
			[]string,
			0,
			len(names),
		)

	for _, name :=
		range names {

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

	client :=
		s.updateClient(addr)

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

	s.mu.RUnlock()

	s.sendMessage(
		addr,
		response,
	)
}

// ============================================================
// 统计频道在线人数
//
// 调用时必须已经持有锁
// ============================================================

func (s *Server) channelMemberCountLocked(
	channelName string,
) int {

	count := 0

	now := time.Now()

	for _, client :=
		range s.clients {

		if client.ChannelName !=
			channelName {

			continue
		}

		if now.Sub(
			client.LastSeen,
		) > clientTimeout {

			continue
		}

		count++
	}

	return count
}

// ============================================================
// 清理频道名称
// ============================================================

func cleanChannelName(
	name string,
) string {

	name =
		strings.TrimSpace(name)

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
		[]rune(name)

	if len(runes) > 24 {

		name =
			string(
				runes[:24],
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
// 抢麦
// ============================================================

func (s *Server) handleTalkStart(
	addr *net.UDPAddr,
) {

	key :=
		addr.String()

	client :=
		s.updateClient(addr)

	s.mu.Lock()

	if _, exists :=
		s.clients[key]; !exists {

		s.mu.Unlock()
		return
	}

	channelName :=
		client.ChannelName

	talker, exists :=
		s.talkers[channelName]

	if exists {

		if talker.Addr.String() ==
			key {

			s.mu.Unlock()

			s.sendMessage(
				addr,
				msgTalkOK,
			)

			return
		}

		s.mu.Unlock()

		s.sendMessage(
			addr,
			msgTalkBusy,
		)

		return
	}

	s.talkers[channelName] =
		&TalkState{
			Addr:      addr,
			StartTime: time.Now(),
		}

	s.mu.Unlock()

	log.Println(
		"========================================",
	)

	log.Println(
		"抢麦成功:",
		key,
	)

	log.Println(
		"用户名:",
		client.Username,
	)

	log.Println(
		"频道:",
		channelName,
	)

	log.Println(
		"========================================",
	)

	s.sendMessage(
		addr,
		msgTalkOK,
	)
}

// ============================================================
// 释放麦权
// ============================================================

func (s *Server) handleTalkStop(
	addr *net.UDPAddr,
) {

	key :=
		addr.String()

	client :=
		s.updateClient(addr)

	s.mu.Lock()

	channelName :=
		client.ChannelName

	talker, exists :=
		s.talkers[channelName]

	if !exists {

		s.mu.Unlock()
		return
	}

	if talker.Addr.String() !=
		key {

		s.mu.Unlock()
		return
	}

	delete(
		s.talkers,
		channelName,
	)

	s.mu.Unlock()

	log.Printf(
		"讲话结束: user=%s channel=%s",
		client.Username,
		channelName,
	)

	s.sendMessage(
		addr,
		msgTalkRelease,
	)
}

// ============================================================
// 讲话 30 秒自动释放
// ============================================================

func (s *Server) talkTimeoutLoop() {

	ticker :=
		time.NewTicker(
			500 * time.Millisecond,
		)

	defer ticker.Stop()

	for range ticker.C {

		s.mu.Lock()

		for channelName, talker :=
			range s.talkers {

			if time.Since(
				talker.StartTime,
			) < talkTimeout {

				continue
			}

			addr :=
				talker.Addr

			delete(
				s.talkers,
				channelName,
			)

			s.mu.Unlock()

			log.Printf(
				"讲话超过30秒，自动释放: channel=%s addr=%s",
				channelName,
				addr.String(),
			)

			s.sendMessage(
				addr,
				msgTalkRelease,
			)

			s.mu.Lock()
		}

		s.mu.Unlock()
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

		removed := false

		s.mu.Lock()

		for key, client :=
			range s.clients {

			if now.Sub(
				client.LastSeen,
			) <= clientTimeout {

				continue
			}

			channelName :=
				client.ChannelName

			if talker, exists :=
				s.talkers[channelName]; exists {

				if talker.Addr.String() ==
					key {

					delete(
						s.talkers,
						channelName,
					)
				}
			}

			delete(
				s.clients,
				key,
			)

			if client.UserID != "" {

				delete(
					s.users,
					client.UserID,
				)
			}

			removed = true

			log.Printf(
				"清理超时客户端: %s user=%s channel=%s",
				key,
				client.Username,
				channelName,
			)
		}

		s.mu.Unlock()

		if removed {

			s.broadcastChannelList()
		}
	}
}

// ============================================================
// 音频转发
//
// 只有当前频道抢到麦的人可以发送
// 只转发到同频道用户
// ============================================================

func (s *Server) relayAudio(
	data []byte,
	sender *net.UDPAddr,
) {

	senderKey :=
		sender.String()

	s.mu.RLock()

	senderClient,
		exists :=
		s.clients[senderKey]

	if !exists {

		s.mu.RUnlock()
		return
	}

	channelName :=
		senderClient.ChannelName

	talker,
		exists :=
		s.talkers[channelName]

	if !exists ||
		talker.Addr.String() !=
			senderKey {

		s.mu.RUnlock()
		return
	}

	clients :=
		make(
			[]*Client,
			0,
			len(s.clients),
		)

	now := time.Now()

	for _, client :=
		range s.clients {

		if now.Sub(
			client.LastSeen,
		) > clientTimeout {

			continue
		}

		if client.ChannelName !=
			channelName {

			continue
		}

		clients =
			append(
				clients,
				client,
			)
	}

	s.mu.RUnlock()

	for _, client :=
		range clients {

		if client.Addr.String() ==
			senderKey {

			continue
		}

		_, err :=
			s.conn.WriteToUDP(
				data,
				client.Addr,
			)

		if err != nil {

			log.Printf(
				"转发失败: %s: %v",
				client.Addr.String(),
				err,
			)

			continue
		}

		log.Printf(
			"转发音频: %d 字节 %s -> %s channel=%s",
			len(data),
			senderKey,
			client.Addr.String(),
			channelName,
		)
	}
}

