import { WalkieUdp } from "@normalized:N&&&entry/src/main/ets/WalkieUdp&";
import type { WalkieUdpMessage } from "@normalized:N&&&entry/src/main/ets/WalkieUdp&";
import { WalkieAudio } from "@normalized:N&&&entry/src/main/ets/WalkieAudio&";
import { WalkieTone } from "@normalized:N&&&entry/src/main/ets/WalkieTone&";
import util from "@ohos:util";
import type { BusinessError } from "@ohos:base";
/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V23.7
 *
 * HarmonyOS 7.0 / API 26
 *
 * 本版本重点：
 *
 * 1. 保持原有稳定 UDP 连接逻辑
 * 2. 登录立即发送 + 快速重试
 * 3. 不使用激进的连接 Watchdog
 * 4. 后台回来后抢麦，若服务器无响应则主动重建 UDP
 * 5. 重连后恢复当前频道
 * 6. 保留 close() 公共接口
 * 7. 抢麦 REQUESTING 超时不再永久卡住
 *
 * VPS 协议完全保持不变。
 * ============================================================
 */
export interface WalkieUser {
    userId: string;
    nickname: string;
}
export interface WalkieChannel {
    name: string;
    onlineCount: number;
    isPrivate: boolean;
    requirePassword: boolean;
}
export interface WalkieNetworkStatus {
    latency: number;
    loss: number;
    quality: string;
    jitter: number;
}
export interface WalkieClientState {
    connected: boolean;
    userId: string;
    nickname: string;
    currentChannel: string;
    currentChannelPrivate: boolean;
    onlineUsers: WalkieUser[];
    channels: WalkieChannel[];
    network: WalkieNetworkStatus;
    talkStatus: string;
    message: string;
}
export type WalkieStateCallback = (state: WalkieClientState) => void;
export class WalkieClient {
    // ============================================================
    // 服务器
    // ============================================================
    private static readonly SERVER_IP: string = '38.146.29.169';
    private static readonly SERVER_PORT: number = 50000;
    // ============================================================
    // 心跳
    // ============================================================
    private static readonly KEEPALIVE_INTERVAL: number = 5000;
    // ============================================================
    // 网络 Ping
    // ============================================================
    private static readonly NETWORK_PING_INTERVAL: number = 2000;
    private static readonly NETWORK_PING_WINDOW: number = 20;
    // ============================================================
    // 登录
    // ============================================================
    private static readonly LOGIN_RETRY_INTERVAL: number = 700;
    private static readonly LOGIN_MAX_RETRIES: number = 5;
    // ============================================================
    // 抢麦
    // ============================================================
    private static readonly TALK_REQUEST_TIMEOUT: number = 2500;
    // ============================================================
    // UDP
    // ============================================================
    private udp: WalkieUdp = new WalkieUdp();
    private audio: WalkieAudio = new WalkieAudio(this.udp);
    private tone: WalkieTone = new WalkieTone();
    // ============================================================
    // Device ID
    // ============================================================
    private deviceId: string = '';
    // ============================================================
    // 连接状态
    // ============================================================
    private connecting: boolean = false;
    private connected: boolean = false;
    private manualDisconnect: boolean = false;
    private reconnecting: boolean = false;
    // ============================================================
    // 登录重试
    // ============================================================
    private loginRetryTimer: number | null = null;
    private loginRetryCount: number = 0;
    // ============================================================
    // 抢麦 Timer
    // ============================================================
    private talkRequestTimer: number | null = null;
    // ============================================================
    // 当前频道
    // ============================================================
    private savedChannelName: string = 'public';
    private savedChannelPassword: string = '';
    private savedChannelPrivate: boolean = false;
    private restoringChannel: boolean = false;
    // ============================================================
    // 定时器
    // ============================================================
    private keepAliveTimer: number | null = null;
    private networkPingTimer: number | null = null;
    // ============================================================
    // Ping
    // ============================================================
    private pingSequence: number = 0;
    private pendingPingSequence: number[] = [];
    private pendingPingTime: Map<number, number> = new Map<number, number>();
    // ============================================================
    // UI State
    // ============================================================
    private state: WalkieClientState = {
        connected: false,
        userId: '',
        nickname: '',
        currentChannel: 'public',
        currentChannelPrivate: false,
        onlineUsers: [],
        channels: [
            {
                name: 'public',
                onlineCount: 0,
                isPrivate: false,
                requirePassword: false
            }
        ],
        network: {
            latency: -1,
            loss: 100,
            quality: '检测中',
            jitter: -1
        },
        talkStatus: 'NONE',
        message: ''
    };
    // ============================================================
    // 状态回调
    // ============================================================
    private stateCallback: WalkieStateCallback | null = null;
    // ============================================================
    // 构造
    // ============================================================
    constructor() {
        this.deviceId =
            this.createDeviceId();
        this.udp.setMessageCallback((message: WalkieUdpMessage): void => {
            this.handleUdpMessage(message);
        });
        this.udp.setErrorCallback((message: string): void => {
            this.handleUdpError(message);
        });
    }
    // ============================================================
    // 状态监听
    // ============================================================
    public setStateCallback(callback: WalkieStateCallback): void {
        this.stateCallback =
            callback;
        this.emitState();
    }
    // ============================================================
    // 获取状态
    // ============================================================
    public getState(): WalkieClientState {
        return {
            connected: this.state.connected,
            userId: this.state.userId,
            nickname: this.state.nickname,
            currentChannel: this.state.currentChannel,
            currentChannelPrivate: this.state.currentChannelPrivate,
            onlineUsers: this.state.onlineUsers.slice(),
            channels: this.state.channels.slice(),
            network: {
                latency: this.state.network.latency,
                loss: this.state.network.loss,
                quality: this.state.network.quality,
                jitter: this.state.network.jitter
            },
            talkStatus: this.state.talkStatus,
            message: this.state.message
        };
    }
    // ============================================================
    // Device ID
    // ============================================================
    public getDeviceId(): string {
        return this.deviceId;
    }
    // ============================================================
    // Socket 状态
    // ============================================================
    private socketReady(): boolean {
        return this.udp.getBound();
    }
    // ============================================================
    // 连接服务器
    // ============================================================
    public async connect(nickname: string): Promise<void> {
        if (this.connecting) {
            return;
        }
        if (this.connected &&
            this.socketReady()) {
            return;
        }
        this.manualDisconnect =
            false;
        this.connecting =
            true;
        /*
         * 保存昵称。
         */
        const clean: string = this.cleanNickname(nickname);
        if (clean.length > 0) {
            this.state.nickname =
                clean;
        }
        /*
         * 保存当前频道。
         */
        if (this.state.currentChannel.length > 0) {
            this.savedChannelName =
                this.state.currentChannel;
        }
        this.savedChannelPrivate =
            this.state.currentChannelPrivate;
        this.stopLoginRetry();
        this.stopTalkRequestTimer();
        this.state.talkStatus =
            'NONE';
        this.state.message =
            '正在连接服务器…';
        this.state.network.quality =
            '连接中';
        this.emitState();
        try {
            /*
             * 每次主动连接都重新打开 UDP。
             */
            await this.udp.open(WalkieClient.SERVER_IP, WalkieClient.SERVER_PORT);
            /*
             * 立即发送登录。
             */
            await this.sendLogin();
            /*
             * 登录快速重发。
             */
            this.startLoginRetry();
            /*
             * 心跳。
             */
            this.startKeepAlive();
            /*
             * Ping。
             */
            this.startNetworkPing();
            this.state.message =
                '登录请求已发送';
            this.emitState();
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE CLIENT: 连接失败 ' +
                `code=${businessError.code} ` +
                `message=${businessError.message}`);
            this.connected =
                false;
            this.state.connected =
                false;
            this.state.network.quality =
                '连接失败';
            this.state.message =
                '连接服务器失败';
            this.stopTimers();
            try {
                await this.udp.close();
            }
            catch {
                // 忽略
            }
            this.emitState();
        }
        finally {
            this.connecting =
                false;
        }
    }
    // ============================================================
    // 登录
    // ============================================================
    private async sendLogin(): Promise<void> {
        if (!this.udp.getBound()) {
            throw new Error('UDP Socket 未打开');
        }
        let loginMessage: string = 'WALKIE_LOGIN:' +
            this.deviceId;
        if (this.state.nickname.length > 0) {
            loginMessage =
                loginMessage +
                    ':' +
                    this.state.nickname;
        }
        await this.udp.send(loginMessage);
    }
    // ============================================================
    // 登录快速重试
    // ============================================================
    private startLoginRetry(): void {
        this.stopLoginRetry();
        this.loginRetryCount =
            0;
        this.loginRetryTimer =
            setInterval((): void => {
                if (this.connected) {
                    this.stopLoginRetry();
                    return;
                }
                if (!this.udp.getBound()) {
                    this.stopLoginRetry();
                    return;
                }
                if (this.loginRetryCount >=
                    WalkieClient.LOGIN_MAX_RETRIES) {
                    this.stopLoginRetry();
                    this.state.message =
                        '等待服务器响应…';
                    this.emitState();
                    return;
                }
                this.loginRetryCount +=
                    1;
                void this.sendLoginRetry();
            }, WalkieClient.LOGIN_RETRY_INTERVAL);
    }
    private async sendLoginRetry(): Promise<void> {
        if (this.connected ||
            !this.udp.getBound()) {
            return;
        }
        try {
            await this.sendLogin();
            console.info('WALKIE CLIENT: ' +
                `Login 重发 ${this.loginRetryCount}`);
        }
        catch {
            // 下一轮继续
        }
    }
    private stopLoginRetry(): void {
        if (this.loginRetryTimer !== null) {
            clearInterval(this.loginRetryTimer);
            this.loginRetryTimer =
                null;
        }
    }
    // ============================================================
    // 断开
    // ============================================================
    public async disconnect(): Promise<void> {
        this.manualDisconnect =
            true;
        this.stopTalkRequestTimer();
        await this.audio.stop();
        this.stopTimers();
        if (this.udp.getBound()) {
            try {
                await this.udp.send('WALKIE_GOODBYE');
            }
            catch {
                // 忽略
            }
        }
        try {
            await this.udp.close();
        }
        catch {
            // 忽略
        }
        this.connected =
            false;
        this.state.connected =
            false;
        this.state.talkStatus =
            'NONE';
        this.state.userId =
            '';
        this.state.onlineUsers =
            [];
        this.state.network.latency =
            -1;
        this.state.network.loss =
            100;
        this.state.network.quality =
            '离线';
        this.state.network.jitter =
            -1;
        this.state.message =
            '已断开服务器';
        this.emitState();
    }
    // ============================================================
    // 重新连接
    // ============================================================
    private async reconnect(): Promise<boolean> {
        if (this.manualDisconnect) {
            return false;
        }
        if (this.reconnecting) {
            return false;
        }
        this.reconnecting =
            true;
        this.stopLoginRetry();
        this.stopTalkRequestTimer();
        /*
         * 停止本地讲话。
         */
        await this.audio.stop();
        /*
         * 保存当前频道。
         */
        if (this.state.currentChannel.length > 0) {
            this.savedChannelName =
                this.state.currentChannel;
        }
        this.savedChannelPrivate =
            this.state.currentChannelPrivate;
        this.connected =
            false;
        this.state.connected =
            false;
        this.state.talkStatus =
            'NONE';
        this.state.network.quality =
            '重连中';
        this.state.message =
            '正在恢复网络连接…';
        this.emitState();
        try {
            /*
             * 彻底关闭旧 Socket。
             */
            try {
                await this.udp.close();
            }
            catch {
                // 忽略
            }
            /*
             * 创建新的 UDP Socket。
             */
            await this.udp.open(WalkieClient.SERVER_IP, WalkieClient.SERVER_PORT);
            /*
             * 立即 Login。
             */
            await this.sendLogin();
            /*
             * 启动后台重发。
             */
            this.startLoginRetry();
            this.startKeepAlive();
            this.startNetworkPing();
            /*
        * 等待服务器确认。
        *
        * 正常情况下收到 WALKIE_CONNECTED
        * 后会立即结束，不再固定等待。
        *
        * 最多等待 1200ms。
        */
            for (let index: number = 0; index < 12; index++) {
                if (this.connected) {
                    await this.restoreSavedChannel();
                    this.state.network.quality =
                        '良好';
                    this.state.message =
                        '网络已恢复';
                    this.emitState();
                    return true;
                }
                await this.sleep(100);
            }
            /*
             * 没等到服务器确认。
             */
            this.state.network.quality =
                '连接中';
            this.state.message =
                '服务器响应较慢';
            this.emitState();
            return false;
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE CLIENT: 重连失败 ' +
                `code=${businessError.code} ` +
                `message=${businessError.message}`);
            try {
                await this.udp.close();
            }
            catch {
                // 忽略
            }
            this.connected =
                false;
            this.state.connected =
                false;
            return false;
        }
        finally {
            this.reconnecting =
                false;
        }
    }
    // ============================================================
    // 抢麦
    // ============================================================
    public async startTalking(): Promise<void> {
        /*
         * ========================================================
         * 情况一：
         *
         * connected=false 或 Socket 不存在。
         *
         * 直接重连。
         * ========================================================
         */
        if (!this.connected ||
            !this.socketReady()) {
            this.state.talkStatus =
                'REQUESTING';
            this.state.message =
                '网络已断开，正在重新连接…';
            this.state.network.quality =
                '重连中';
            this.emitState();
            const success: boolean = await this.reconnect();
            if (!success ||
                !this.connected ||
                !this.socketReady()) {
                this.state.talkStatus =
                    'NONE';
                this.state.message =
                    '网络未恢复，请再按一次说话';
                this.emitState();
                return;
            }
        }
        if (this.state.talkStatus ===
            'BUSY') {
            return;
        }
        if (this.state.talkStatus ===
            'ALLOWED') {
            return;
        }
        this.stopTalkRequestTimer();
        this.state.talkStatus =
            'REQUESTING';
        this.state.message =
            '正在抢麦…';
        this.emitState();
        try {
            /*
             * 发送抢麦。
             */
            await this.udp.send('WALKIE_TALK_START');
            /*
             * ========================================================
             * 注意：
             *
             * UDP Socket 仍然 bound，
             * 并不能证明后台恢复以后服务器一定能收到。
             *
             * 所以超时以后也主动重建连接。
             * ========================================================
             */
            this.talkRequestTimer =
                setTimeout((): void => {
                    this.talkRequestTimer =
                        null;
                    if (this.state.talkStatus !==
                        'REQUESTING') {
                        return;
                    }
                    this.state.message =
                        '抢麦响应超时，正在恢复网络…';
                    this.state.network.quality =
                        '重连中';
                    this.emitState();
                    void this.reconnect()
                        .then((success: boolean): void => {
                        this.state.talkStatus =
                            'NONE';
                        this.state.message =
                            success
                                ? '网络已恢复，请重新抢麦'
                                : '网络恢复失败，请重试';
                        this.emitState();
                    });
                }, WalkieClient.TALK_REQUEST_TIMEOUT);
        }
        catch {
            this.stopTalkRequestTimer();
            this.state.talkStatus =
                'NONE';
            this.state.message =
                '抢麦发送失败，正在恢复网络…';
            this.emitState();
            const success: boolean = await this.reconnect();
            this.state.message =
                success
                    ? '网络已恢复，请重新抢麦'
                    : '网络恢复失败，请稍后重试';
            this.emitState();
        }
    }
    // ============================================================
    // 释放麦权
    // ============================================================
    public async stopTalking(): Promise<void> {
        this.stopTalkRequestTimer();
        await this.audio.stop();
        if (this.connected &&
            this.udp.getBound()) {
            try {
                await this.udp.send('WALKIE_TALK_STOP');
            }
            catch {
                // 忽略
            }
        }
        this.state.talkStatus =
            'NONE';
        this.emitState();
    }
    // ============================================================
    // 关闭
    // ============================================================
    public async close(): Promise<void> {
        await this.disconnect();
    }
    // ============================================================
    // 恢复频道
    // ============================================================
    private async restoreSavedChannel(): Promise<void> {
        const channel: string = this.savedChannelName.trim();
        if (channel.length === 0 ||
            channel === 'public') {
            return;
        }
        if (!this.connected ||
            !this.udp.getBound()) {
            return;
        }
        try {
            let message: string = 'WALKIE_JOIN_CHANNEL:' +
                channel;
            if (this.savedChannelPassword.length > 0) {
                message =
                    message +
                        ':' +
                        this.savedChannelPassword;
            }
            this.restoringChannel =
                true;
            await this.udp.send(message);
        }
        catch {
            this.restoringChannel =
                false;
        }
    }
    // ============================================================
    // 设置昵称
    // ============================================================
    public async setNickname(nickname: string): Promise<void> {
        const clean: string = this.cleanNickname(nickname);
        if (clean.length === 0) {
            return;
        }
        this.state.nickname =
            clean;
        if (this.connected &&
            this.udp.getBound()) {
            try {
                await this.udp.send('WALKIE_SET_NICKNAME:' +
                    clean);
            }
            catch {
                this.state.message =
                    '昵称同步失败';
                this.emitState();
            }
        }
        this.emitState();
    }
    // ============================================================
    // 频道列表
    // ============================================================
    public async requestChannelList(): Promise<void> {
        if (!this.connected) {
            return;
        }
        try {
            await this.udp.send('WALKIE_CHANNEL_LIST');
        }
        catch {
            this.state.message =
                '请求频道列表失败';
            this.emitState();
        }
    }
    // ============================================================
    // 用户列表
    // ============================================================
    public async requestUserList(): Promise<void> {
        if (!this.connected) {
            return;
        }
        try {
            await this.udp.send('WALKIE_USER_LIST');
        }
        catch {
            this.state.message =
                '请求在线人员失败';
            this.emitState();
        }
    }
    // ============================================================
    // 频道成员
    // ============================================================
    public async requestChannelMembers(): Promise<void> {
        if (!this.connected) {
            return;
        }
        try {
            await this.udp.send('WALKIE_CHANNEL_MEMBERS');
        }
        catch {
            this.state.message =
                '请求频道成员失败';
            this.emitState();
        }
    }
    // ============================================================
    // 加入频道
    // ============================================================
    public async joinChannel(channelName: string, password: string = ''): Promise<void> {
        if (!this.connected) {
            return;
        }
        const cleanChannel: string = channelName.trim();
        if (cleanChannel.length === 0) {
            return;
        }
        this.savedChannelName =
            cleanChannel;
        this.savedChannelPassword =
            password.trim();
        let message: string = 'WALKIE_JOIN_CHANNEL:' +
            cleanChannel;
        if (password.trim().length > 0) {
            message =
                message +
                    ':' +
                    password.trim();
        }
        try {
            await this.udp.send(message);
        }
        catch {
            this.state.message =
                '加入频道失败';
            this.emitState();
        }
    }
    // ============================================================
    // 创建频道
    // ============================================================
    public async createChannel(name: string, isPrivate: boolean, password: string = ''): Promise<void> {
        if (!this.connected) {
            return;
        }
        const cleanName: string = name.trim();
        if (cleanName.length === 0) {
            return;
        }
        let message: string;
        if (isPrivate) {
            message =
                'WALKIE_CREATE_CHANNEL:' +
                    cleanName +
                    ':PRIVATE:' +
                    password.trim();
        }
        else {
            message =
                'WALKIE_CREATE_CHANNEL:' +
                    cleanName +
                    ':PUBLIC';
        }
        try {
            await this.udp.send(message);
        }
        catch {
            this.state.message =
                '创建频道失败';
            this.emitState();
        }
    }
    // ============================================================
    // 删除频道
    // ============================================================
    public async deleteChannel(channelName: string): Promise<void> {
        if (!this.connected) {
            return;
        }
        const cleanChannel: string = channelName.trim();
        if (cleanChannel.length === 0 ||
            cleanChannel === 'public') {
            return;
        }
        try {
            await this.udp.send('WALKIE_DELETE_CHANNEL:' +
                cleanChannel);
        }
        catch {
            this.state.message =
                '删除频道失败';
            this.emitState();
        }
    }
    // ============================================================
    // UDP 错误
    // ============================================================
    private handleUdpError(message: string): void {
        console.error('WALKIE UDP ERROR:', message);
        if (this.manualDisconnect) {
            return;
        }
        this.connected =
            false;
        this.state.connected =
            false;
        this.stopTalkRequestTimer();
        void this.audio.stop();
        this.state.talkStatus =
            'NONE';
        this.state.network.quality =
            '网络异常';
        this.state.message =
            message;
        this.emitState();
    }
    // ============================================================
    // UDP 消息
    // ============================================================
    private handleUdpMessage(message: WalkieUdpMessage): void {
        const text: string = this.decodeText(message.data);
        if (text.length === 0) {
            return;
        }
        console.info('WALKIE RX:', text);
        // ----------------------------------------------------------
        // CONNECTED
        // ----------------------------------------------------------
        if (text ===
            'WALKIE_CONNECTED') {
            this.connected =
                true;
            this.state.connected =
                true;
            this.stopLoginRetry();
            this.state.message =
                '服务器连接正常';
            this.state.network.quality =
                '良好';
            this.emitState();
            /*
             * 恢复频道。
             */
            void this.restoreSavedChannel();
            /*
             * 同步服务器数据。
             */
            void this.requestUserList();
            void this.requestChannelList();
            void this.requestChannelMembers();
            return;
        }
        // ----------------------------------------------------------
        // KEEPALIVE
        // ----------------------------------------------------------
        if (text ===
            'WALKIE_KEEPALIVE') {
            if (!this.connected) {
                this.connected =
                    true;
                this.state.connected =
                    true;
                this.stopLoginRetry();
                this.state.message =
                    '服务器连接正常';
                this.state.network.quality =
                    '良好';
                this.emitState();
            }
            return;
        }
        // ----------------------------------------------------------
        // USER_OK
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_USER_OK:') === 0) {
            this.handleUserOk(text);
            return;
        }
        // ----------------------------------------------------------
        // USER_STATUS
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_USER_STATUS:') === 0) {
            this.handleUserStatus(text);
            return;
        }
        // ----------------------------------------------------------
        // USER_LIST
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_USER_LIST:') === 0) {
            this.handleUserList(text);
            return;
        }
        // ----------------------------------------------------------
        // CHANNEL_MEMBERS
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_CHANNEL_MEMBERS:') === 0) {
            this.handleChannelMembers(text);
            return;
        }
        // ----------------------------------------------------------
        // CHANNEL_LIST
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_CHANNEL_LIST:') === 0) {
            this.handleChannelList(text);
            return;
        }
        // ----------------------------------------------------------
        // CHANNEL_INFO
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_CHANNEL_INFO:') === 0) {
            this.handleChannelInfo(text);
            return;
        }
        // ----------------------------------------------------------
        // CHANNEL_JOINED
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_CHANNEL_JOINED:') === 0) {
            this.handleChannelJoined(text);
            return;
        }
        // ----------------------------------------------------------
        // CHANNEL_ERROR
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_CHANNEL_ERROR:') === 0) {
            this.restoringChannel =
                false;
            this.state.message =
                text.substring('WALKIE_CHANNEL_ERROR:'.length);
            this.emitState();
            return;
        }
        // ----------------------------------------------------------
        // CHANNEL_DELETED
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_CHANNEL_DELETED:') === 0) {
            this.handleChannelDeleted(text);
            return;
        }
        // ----------------------------------------------------------
        // TALK OK
        // ----------------------------------------------------------
        if (text ===
            'WALKIE_TALK_OK') {
            this.stopTalkRequestTimer();
            this.state.talkStatus =
                'ALLOWED';
            this.state.message =
                '麦克风已获得';
            this.emitState();
            void this.playGrantedToneAndStartAudio();
            return;
        }
        // ----------------------------------------------------------
        // TALK BUSY
        // ----------------------------------------------------------
        if (text ===
            'WALKIE_TALK_BUSY') {
            this.stopTalkRequestTimer();
            this.state.talkStatus =
                'BUSY';
            this.state.message =
                '麦克风正在使用中';
            this.emitState();
            return;
        }
        // ----------------------------------------------------------
        // TALK RELEASED
        // ----------------------------------------------------------
        if (text ===
            'WALKIE_TALK_RELEASED') {
            this.stopTalkRequestTimer();
            void this.audio.stop();
            this.state.talkStatus =
                'RELEASED';
            this.state.message =
                '麦权已释放';
            this.emitState();
            return;
        }
        // ----------------------------------------------------------
        // TALKING
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_TALKING:') === 0) {
            this.state.message =
                text;
            this.emitState();
            return;
        }
        // ----------------------------------------------------------
        // NET PONG
        // ----------------------------------------------------------
        if (text.indexOf('WALKIE_NET_PONG:') === 0) {
            this.handleNetworkPong(text);
            return;
        }
    }
    // ============================================================
    // 启动本地音频
    // ============================================================
    // ============================================================
    // 抢麦成功提示音 → 启动麦克风
    // ============================================================
    private async playGrantedToneAndStartAudio(): Promise<void> {
        await this.tone.playTalkGranted();
        await this.startLocalAudio();
    }
    private async startLocalAudio(): Promise<void> {
        try {
            const started: boolean = await this.audio.start();
            if (started) {
                this.state.talkStatus =
                    'ALLOWED';
                this.state.message =
                    '正在讲话';
                this.emitState();
                return;
            }
            this.state.talkStatus =
                'NONE';
            this.state.message =
                '麦克风启动失败';
            this.emitState();
            if (this.connected &&
                this.udp.getBound()) {
                try {
                    await this.udp.send('WALKIE_TALK_STOP');
                }
                catch {
                    // 忽略
                }
            }
        }
        catch {
            this.state.talkStatus =
                'NONE';
            this.state.message =
                '麦克风启动异常';
            this.emitState();
        }
    }
    // ============================================================
    // USER_OK
    // ============================================================
    private handleUserOk(text: string): void {
        const payload: string = text.substring('WALKIE_USER_OK:'.length);
        const parts: string[] = payload.split(':');
        if (parts.length > 0) {
            const id: string = parts[0].trim();
            if (id.length > 0) {
                this.state.userId =
                    id;
            }
        }
        if (parts.length > 1) {
            const name: string = parts[1].trim();
            if (name.length > 0) {
                this.state.nickname =
                    name;
            }
        }
        if (parts.length > 2) {
            const channel: string = parts[2].trim();
            if (channel.length > 0 &&
                !this.restoringChannel) {
                this.state.currentChannel =
                    channel;
                this.savedChannelName =
                    channel;
            }
        }
        this.emitState();
    }
    // ============================================================
    // USER_STATUS
    // ============================================================
    private handleUserStatus(text: string): void {
        const payload: string = text.substring('WALKIE_USER_STATUS:'.length);
        const parts: string[] = payload.split(':');
        if (parts.length > 0) {
            const id: string = parts[0].trim();
            if (id.length > 0) {
                this.state.userId =
                    id;
            }
        }
        if (parts.length > 1) {
            const name: string = parts[1].trim();
            if (name.length > 0) {
                this.state.nickname =
                    name;
            }
        }
        if (parts.length > 2) {
            const channel: string = parts[2].trim();
            if (channel.length > 0 &&
                !this.restoringChannel) {
                this.state.currentChannel =
                    channel;
                this.savedChannelName =
                    channel;
            }
        }
        this.emitState();
    }
    // ============================================================
    // USER_LIST
    // ============================================================
    private handleUserList(text: string): void {
        const payload: string = text.substring('WALKIE_USER_LIST:'.length);
        const separator: number = payload.indexOf(':');
        if (separator < 0) {
            return;
        }
        const channel: string = payload
            .substring(0, separator)
            .trim();
        const members: string = payload.substring(separator + 1);
        this.state.onlineUsers =
            this.parseUsers(members);
        if (channel.length > 0 &&
            !this.restoringChannel) {
            this.state.currentChannel =
                channel;
            this.savedChannelName =
                channel;
        }
        this.emitState();
    }
    // ============================================================
    // CHANNEL_MEMBERS
    // ============================================================
    private handleChannelMembers(text: string): void {
        const payload: string = text.substring('WALKIE_CHANNEL_MEMBERS:'.length);
        const separator: number = payload.indexOf(':');
        if (separator < 0) {
            this.state.onlineUsers =
                [];
            this.emitState();
            return;
        }
        const channel: string = payload
            .substring(0, separator)
            .trim();
        const members: string = payload.substring(separator + 1);
        this.state.onlineUsers =
            this.parseUsers(members);
        if (channel.length > 0 &&
            !this.restoringChannel) {
            this.state.currentChannel =
                channel;
            this.savedChannelName =
                channel;
        }
        this.emitState();
    }
    // ============================================================
    // CHANNEL_LIST
    // ============================================================
    private handleChannelList(text: string): void {
        const payload: string = text.substring('WALKIE_CHANNEL_LIST:'.length);
        if (payload.trim().length === 0) {
            return;
        }
        const result: WalkieChannel[] = [];
        const items: string[] = payload.split(';');
        for (let index: number = 0; index < items.length; index++) {
            const item: string = items[index];
            const parts: string[] = item.split(',');
            let name: string = '';
            if (parts.length > 0) {
                name =
                    parts[0].trim();
            }
            if (name.length === 0) {
                continue;
            }
            let type: string = 'PUBLIC';
            if (parts.length > 1) {
                type =
                    parts[1]
                        .trim()
                        .toUpperCase();
            }
            let count: number = 0;
            if (parts.length > 2) {
                const parsed: number = Number.parseInt(parts[2].trim(), 10);
                if (!Number.isNaN(parsed)) {
                    count =
                        Math.max(0, parsed);
                }
            }
            result.push({
                name: name,
                onlineCount: count,
                isPrivate: type === 'PRIVATE',
                requirePassword: type === 'PRIVATE'
            });
        }
        if (result.length > 0) {
            this.state.channels =
                result;
            this.emitState();
        }
    }
    // ============================================================
    // CHANNEL_INFO
    // ============================================================
    private handleChannelInfo(text: string): void {
        const payload: string = text.substring('WALKIE_CHANNEL_INFO:'.length);
        const parts: string[] = payload.split(',');
        let name: string = '';
        let type: string = 'PUBLIC';
        if (parts.length > 0) {
            name =
                parts[0].trim();
        }
        if (parts.length > 1) {
            type =
                parts[1]
                    .trim()
                    .toUpperCase();
        }
        if (name.length > 0) {
            this.state.currentChannel =
                name;
            this.state.currentChannelPrivate =
                type === 'PRIVATE';
            this.savedChannelName =
                name;
            this.savedChannelPrivate =
                this.state.currentChannelPrivate;
        }
        this.emitState();
    }
    // ============================================================
    // CHANNEL_JOINED
    // ============================================================
    private handleChannelJoined(text: string): void {
        const payload: string = text.substring('WALKIE_CHANNEL_JOINED:'.length);
        const parts: string[] = payload.split(',');
        let channel: string = '';
        if (parts.length > 0) {
            channel =
                parts[0].trim();
        }
        if (channel.length > 0) {
            this.state.currentChannel =
                channel;
            this.savedChannelName =
                channel;
            this.state.onlineUsers =
                [];
            this.state.message =
                '已进入频道：' +
                    channel;
            this.restoringChannel =
                false;
        }
        this.emitState();
        void this.requestChannelMembers();
    }
    // ============================================================
    // CHANNEL_DELETED
    // ============================================================
    private handleChannelDeleted(text: string): void {
        const deleted: string = text.substring('WALKIE_CHANNEL_DELETED:'.length)
            .trim();
        const remain: WalkieChannel[] = [];
        for (let index: number = 0; index < this.state.channels.length; index++) {
            const channel: WalkieChannel = this.state.channels[index];
            if (channel.name !==
                deleted) {
                remain.push(channel);
            }
        }
        this.state.channels =
            remain;
        if (deleted ===
            this.state.currentChannel) {
            this.state.currentChannel =
                'public';
            this.state.currentChannelPrivate =
                false;
            this.savedChannelName =
                'public';
            this.savedChannelPassword =
                '';
            this.savedChannelPrivate =
                false;
            this.state.onlineUsers =
                [];
        }
        this.state.message =
            '频道已删除：' +
                deleted;
        this.emitState();
    }
    // ============================================================
    // NET PONG
    // ============================================================
    private handleNetworkPong(text: string): void {
        const payload: string = text.substring('WALKIE_NET_PONG:'.length);
        const parts: string[] = payload.split(':');
        if (parts.length < 2) {
            return;
        }
        const sequence: number = Number.parseInt(parts[0].trim(), 10);
        if (Number.isNaN(sequence)) {
            return;
        }
        const sentTime: number | undefined = this.pendingPingTime.get(sequence);
        if (sentTime === undefined) {
            return;
        }
        this.pendingPingTime.delete(sequence);
        this.removePendingSequence(sequence);
        const latency: number = Math.max(0, Date.now() -
            sentTime);
        this.state.network.latency =
            latency;
        this.state.network.loss =
            0;
        if (latency < 80) {
            this.state.network.quality =
                '良好';
        }
        else if (latency < 180) {
            this.state.network.quality =
                '一般';
        }
        else {
            this.state.network.quality =
                '较差';
        }
        this.emitState();
    }
    // ============================================================
    // KeepAlive
    // ============================================================
    private startKeepAlive(): void {
        this.stopKeepAlive();
        this.keepAliveTimer =
            setInterval((): void => {
                if (this.udp.getBound()) {
                    void this.sendKeepAlive();
                }
            }, WalkieClient.KEEPALIVE_INTERVAL);
    }
    private stopKeepAlive(): void {
        if (this.keepAliveTimer !== null) {
            clearInterval(this.keepAliveTimer);
            this.keepAliveTimer =
                null;
        }
    }
    private async sendKeepAlive(): Promise<void> {
        if (!this.udp.getBound()) {
            return;
        }
        try {
            await this.udp.send('WALKIE_KEEPALIVE');
        }
        catch {
            this.connected =
                false;
            this.state.connected =
                false;
            this.state.talkStatus =
                'NONE';
            this.state.network.quality =
                '网络异常';
            this.state.message =
                '心跳发送失败';
            this.emitState();
        }
    }
    // ============================================================
    // Network Ping
    // ============================================================
    private startNetworkPing(): void {
        this.stopNetworkPing();
        this.networkPingTimer =
            setInterval((): void => {
                void this.sendNetworkPing();
            }, WalkieClient.NETWORK_PING_INTERVAL);
        void this.sendNetworkPing();
    }
    private stopNetworkPing(): void {
        if (this.networkPingTimer !== null) {
            clearInterval(this.networkPingTimer);
            this.networkPingTimer =
                null;
        }
        this.pendingPingSequence =
            [];
        this.pendingPingTime.clear();
    }
    private async sendNetworkPing(): Promise<void> {
        if (!this.connected ||
            !this.udp.getBound()) {
            return;
        }
        const sequence: number = this.pingSequence;
        this.pingSequence +=
            1;
        const timestamp: number = Date.now();
        this.pendingPingSequence.push(sequence);
        this.pendingPingTime.set(sequence, timestamp);
        while (this.pendingPingSequence.length >
            WalkieClient.NETWORK_PING_WINDOW) {
            const first: number = this.pendingPingSequence[0];
            this.pendingPingSequence.splice(0, 1);
            this.pendingPingTime.delete(first);
        }
        try {
            await this.udp.send('WALKIE_NET_PING:' +
                sequence +
                ':' +
                timestamp);
        }
        catch {
            this.pendingPingTime.delete(sequence);
            this.removePendingSequence(sequence);
            this.connected =
                false;
            this.state.connected =
                false;
            this.state.talkStatus =
                'NONE';
            this.state.network.quality =
                '网络异常';
            this.state.message =
                '网络连接已失效';
            this.emitState();
        }
    }
    // ============================================================
    // 停止全部定时器
    // ============================================================
    private stopTimers(): void {
        this.stopLoginRetry();
        this.stopKeepAlive();
        this.stopNetworkPing();
    }
    // ============================================================
    // 抢麦 Timer
    // ============================================================
    private stopTalkRequestTimer(): void {
        if (this.talkRequestTimer !== null) {
            clearTimeout(this.talkRequestTimer);
            this.talkRequestTimer =
                null;
        }
    }
    // ============================================================
    // 删除 Pending Sequence
    // ============================================================
    private removePendingSequence(sequence: number): void {
        const result: number[] = [];
        for (let index: number = 0; index < this.pendingPingSequence.length; index++) {
            const item: number = this.pendingPingSequence[index];
            if (item !== sequence) {
                result.push(item);
            }
        }
        this.pendingPingSequence =
            result;
    }
    // ============================================================
    // 用户解析
    // ============================================================
    private parseUsers(value: string): WalkieUser[] {
        const result: WalkieUser[] = [];
        const clean: string = value.trim();
        if (clean.length === 0) {
            return result;
        }
        const items: string[] = clean.split(';');
        for (let index: number = 0; index < items.length; index++) {
            const item: string = items[index];
            const separator: number = item.indexOf('|');
            if (separator <= 0) {
                continue;
            }
            const userId: string = item
                .substring(0, separator)
                .trim();
            const nickname: string = item
                .substring(separator + 1)
                .trim();
            if (userId.length === 0) {
                continue;
            }
            result.push({
                userId: userId,
                nickname: nickname.length > 0
                    ? nickname
                    : '未命名用户'
            });
        }
        const unique: WalkieUser[] = [];
        for (let index: number = 0; index < result.length; index++) {
            const user: WalkieUser = result[index];
            let exists: boolean = false;
            for (let inner: number = 0; inner < unique.length; inner++) {
                if (unique[inner].userId ===
                    user.userId) {
                    exists =
                        true;
                    break;
                }
            }
            if (!exists) {
                unique.push(user);
            }
        }
        return unique;
    }
    // ============================================================
    // UTF-8
    // ============================================================
    private decodeText(data: string | ArrayBuffer): string {
        if (typeof data === 'string') {
            return data.trim();
        }
        try {
            const bytes: Uint8Array = new Uint8Array(data);
            if (bytes.length === 0) {
                return '';
            }
            const decoder: util.TextDecoder = util.TextDecoder.create('utf-8', {
                ignoreBOM: true
            });
            return decoder
                .decode(bytes)
                .trim();
        }
        catch {
            console.error('WALKIE: UTF-8 解码失败');
            return '';
        }
    }
    // ============================================================
    // 清理昵称
    // ============================================================
    private cleanNickname(value: string): string {
        let result: string = value.trim();
        result =
            result.replaceAll(':', '');
        result =
            result.replaceAll(';', '');
        result =
            result.replaceAll(',', '');
        result =
            result.replaceAll('\n', '');
        result =
            result.replaceAll('\r', '');
        result =
            result.substring(0, 20);
        return result.trim();
    }
    // ============================================================
    // Device ID
    // ============================================================
    private createDeviceId(): string {
        const time: string = Date.now()
            .toString(36)
            .toUpperCase();
        const random: string = Math.floor(Math.random() *
            0xFFFFFF)
            .toString(36)
            .toUpperCase();
        return ('HARMONY-' +
            time +
            '-' +
            random);
    }
    // ============================================================
    // Sleep
    // ============================================================
    private sleep(milliseconds: number): Promise<void> {
        return new Promise((resolve: () => void): void => {
            setTimeout(resolve, milliseconds);
        });
    }
    // ============================================================
    // 状态通知
    // ============================================================
    private emitState(): void {
        const callback: WalkieStateCallback | null = this.stateCallback;
        if (callback !== null) {
            callback(this.getState());
        }
    }
}
