import { WalkieUdp } from "@normalized:N&&&entry/src/main/ets/WalkieUdp&";
import type { WalkieUdpMessage } from "@normalized:N&&&entry/src/main/ets/WalkieUdp&";
import { WalkieAudio } from "@normalized:N&&&entry/src/main/ets/WalkieAudio&";
import { WalkieTone } from "@normalized:N&&&entry/src/main/ets/WalkieTone&";
import { WalkiePlayback } from "@normalized:N&&&entry/src/main/ets/WalkiePlayback&";
import { WalkieAudioPacket } from "@normalized:N&&&entry/src/main/ets/WalkieAudioPacket&";
import util from "@ohos:util";
import type { BusinessError } from "@ohos:base";
import connection from "@ohos:net.connection";
/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V24.8.3
 *
 * HarmonyOS 7.0 / API 26
 *
 * 本版本重点：
 *
 * 1. 稳定 UDP 连接
 * 2. HELLO → CONNECTED → LOGIN
 * 3. 持续自动重连
 * 4. 弱网防误判
 * 5. 一个 Client 只允许一个重连循环
 * 6. 重连失败持续尝试
 * 7. 当前频道自动恢复
 * 8. 在线人员自动同步
 * 9. 抢麦
 * 10. 提示音
 * 11. Android → HarmonyOS 音频接收
 * 12. W23A + Legacy Opus 双协议兼容
 * 13. DeviceID 由外部持久化保存
 *
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
    /*
     * Ping 只是质量检测，
     * 不能因为一次超时就马上判定彻底断网。
     */
    private static readonly NETWORK_PING_INTERVAL: number = 2000;
    private static readonly NETWORK_PING_WINDOW: number = 20;
    /*
     * 单次 Ping 最大等待时间。
     *
     * 从原来的 1800ms 提高到 5000ms，
     * 避免弱网下偶发延迟直接触发重连。
     */
    private static readonly NETWORK_PING_TIMEOUT: number = 1800;
    /*
     * 连续多少个 Ping 超时才真正判定连接失效。
     */
    private static readonly SERVER_ACTIVITY_TIMEOUT: number = 120000;
    // ============================================================
    // 自动重连
    // ============================================================
    /*
     * 第一轮快速恢复。
     */
    private static readonly AUTO_RECONNECT_INITIAL_DELAY: number = 300;
    /*
     * 最大重连间隔。
     */
    private static readonly AUTO_RECONNECT_MAX_DELAY: number = 1500;
    /*
     * 自动重连每一轮等待服务器 CONNECTED 的最大时间。
     *
     * 不是等 1.5 秒，而是留出 3 秒握手窗口。
     */
    private static readonly RECONNECT_HANDSHAKE_TIMEOUT: number = 3000;
    /*
     * 重连循环每一轮的最短等待。
     */
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
    private playback: WalkiePlayback = new WalkiePlayback();
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
    private connectionGeneration: number = 0;
    /*
     * 自动重连循环是否正在运行。
     *
     * 这是 V24.2 的关键锁。
     *
     * 永远只允许一个重连循环。
     */
    private autoReconnectLoopRunning: boolean = false;
    /*
     * 当前重连尝试次数。
     */
    private autoReconnectAttempt: number = 0;
    /*
     * 当前重连延迟。
     */
    private autoReconnectDelay: number = WalkieClient.AUTO_RECONNECT_INITIAL_DELAY;
    // ============================================================
    // 后台恢复
    // ============================================================
    private backgroundRecoveryTimer: number | null = null;
    private backgroundRecoveryBusy: boolean = false;
    private lastForegroundTime: number = 0;
    private readonly BACKGROUND_RECOVERY_INTERVAL: number = 5000;
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
    private lastServerActivityTime: number = 0;
    private pingResults: boolean[] = [];
    // ============================================================
    // Ping
    // ============================================================
    private pingSequence: number = 0;
    private pendingPingSequence: number[] = [];
    private pendingPingTime: Map<number, number> = new Map<number, number>();
    /*
     * Android V23.1 → HarmonyOS V24.6
     * 网络抖动计算。
     */
    private lastMeasuredLatency: number = -1;
    /*
     * 连续 Ping 超时次数。
     */
    // ============================================================
    // V24.3 网络切换迁移
    // ============================================================
    /*
     * HarmonyOS 默认网络发生变化时，立即准备 UDP 端点迁移。
     *
     * 注意：这里不调用完整 reconnect()，避免切网时停止音频播放。
     */
    private netConnection: connection.NetConnection | null = null;
    private networkListenerRegistered: boolean = false;
    private networkMigrationTimer: number | null = null;
    private networkMigrationRunning: boolean = false;
    // 网络迁移完成时间
    // 用于防止短时间内重复触发迁移。
    private lastNetworkMigrationTime: number = 0;
    private lastBearerSignature: string = '';
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
        /*
         * V24.3：从 Client 创建时就监听默认网络变化。
         */
        this.registerNetworkListener();
    }
    // ============================================================
    // V24.3 默认网络监听
    // ============================================================
    private registerNetworkListener(): void {
        if (this.networkListenerRegistered) {
            return;
        }
        try {
            const netConnection: connection.NetConnection = connection.createNetConnection();
            this.netConnection =
                netConnection;
            netConnection.on('netAvailable', (): void => {
                console.info('WALKIE NET: ★netAvailable★');
                this.refreshBearerSignature('netAvailable', true);
            });
            netConnection.on('netCapabilitiesChange', (data: connection.NetCapabilityInfo): void => {
                const bearer: string = this.readBearerSignatureFromInfo(data);
                console.info('WALKIE NET: netCapabilitiesChange ' +
                    `bearer=${bearer}`);
                this.handlePossibleNetworkChange('netCapabilitiesChange', bearer);
            });
            netConnection.on('netConnectionPropertiesChange', (): void => {
                /*
                 * IP / 路由属性发生变化也检查一次默认网络。
                 * 只有 bearer 签名真正变化才会触发迁移。
                 */
                this.refreshBearerSignature('netConnectionPropertiesChange', true);
            });
            netConnection.on('netLost', (): void => {
                console.warn('WALKIE NET: netLost，等待新默认网络');
                /*
                 * netLost 时绝不立即重建 UDP。
                 * 先等待新的默认网络出现，再比较 bearer。
                 * 这样可以避免 Wi-Fi 断开到蜂窝网络接管之间，
                 * 因过早重建一次 UDP 而人为增加新的断档。
                 */
                setTimeout((): void => {
                    this.refreshBearerSignature('netLost', true);
                }, 50);
            });
            netConnection.on('netUnavailable', (): void => {
                console.warn('WALKIE NET: netUnavailable');
            });
            netConnection.register((error?: BusinessError): void => {
                if (error) {
                    console.error('WALKIE NET: 网络监听注册失败 ' +
                        `code=${error.code} message=${error.message}`);
                    return;
                }
                this.networkListenerRegistered =
                    true;
                console.info('WALKIE NET: ★默认网络监听已启动★');
                this.refreshBearerSignature('register', false);
            });
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE NET: 创建网络监听失败 ' +
                `code=${businessError.code} message=${businessError.message}`);
        }
    }
    private readBearerSignatureFromInfo(data: connection.NetCapabilityInfo): string {
        try {
            const bearerTypes = data.netCap.bearerTypes;
            return JSON.stringify(bearerTypes);
        }
        catch {
            return '';
        }
    }
    private getCurrentBearerSignature(): string {
        try {
            const defaultNet = connection.getDefaultNetSync();
            const netCapabilities = connection.getNetCapabilitiesSync(defaultNet);
            return JSON.stringify(netCapabilities.bearerTypes);
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.warn('WALKIE NET: 获取默认网络失败 ' +
                `code=${businessError.code} message=${businessError.message}`);
            return '';
        }
    }
    private refreshBearerSignature(reason: string, triggerMigration: boolean): void {
        const signature: string = this.getCurrentBearerSignature();
        if (signature.length ===
            0) {
            return;
        }
        this.handlePossibleNetworkChange(reason, signature, triggerMigration);
    }
    private handlePossibleNetworkChange(reason: string, signature: string, triggerMigration: boolean = true): void {
        if (this.lastBearerSignature.length ===
            0) {
            this.lastBearerSignature =
                signature;
            console.info('WALKIE NET: 初始默认网络 ' +
                `bearer=${signature}`);
            return;
        }
        if (signature ===
            this.lastBearerSignature) {
            return;
        }
        const oldSignature: string = this.lastBearerSignature;
        this.lastBearerSignature =
            signature;
        console.warn('WALKIE NET: ★默认网络发生变化★ ' +
            `old=${oldSignature} new=${signature} reason=${reason}`);
        if (!triggerMigration) {
            return;
        }
        if (this.manualDisconnect ||
            !this.connected ||
            !this.socketReady()) {
            return;
        }
        this.scheduleNetworkMigration(reason, 0);
    }
    private scheduleNetworkMigration(reason: string, delayMs: number): void {
        if (this.manualDisconnect ||
            !this.connected) {
            return;
        }
        if (this.networkMigrationRunning) {
            return;
        }
        if (this.networkMigrationTimer !==
            null) {
            clearTimeout(this.networkMigrationTimer);
            this.networkMigrationTimer =
                null;
        }
        this.state.network.quality =
            '网络切换中';
        this.state.message =
            '网络切换，正在快速迁移…';
        this.emitState();
        this.networkMigrationTimer =
            setTimeout((): void => {
                this.networkMigrationTimer =
                    null;
                void this.migrateUdpEndpoint(reason);
            }, delayMs) as number;
    }
    private async migrateUdpEndpoint(reason: string): Promise<void> {
        if (this.networkMigrationRunning ||
            this.manualDisconnect ||
            !this.connected) {
            return;
        }
        this.networkMigrationRunning =
            true;
        const startTime: number = Date.now();
        try {
            console.warn('WALKIE NET MIGRATION: ★开始快速UDP端点迁移★ ' +
                `reason=${reason}`);
            // ========================================================
            // 第一步：创建新 UDP Socket
            // ========================================================
            /*
             * 不发送 GOODBYE。
             *
             * migrate() 不会先关闭旧 Socket。
             *
             * 新 Socket bind 成功以后：
             *
             * old Socket
             *       +
             * new Socket
             *
             * 会短暂同时存在。
             */
            await this.udp.migrate(WalkieClient.SERVER_IP, WalkieClient.SERVER_PORT, 3000);
            // ========================================================
            // 第二步：清除旧 Ping 状态
            // ========================================================
            this.lastServerActivityTime =
                Date.now();
            this.pendingPingSequence =
                [];
            this.pendingPingTime.clear();
            // ========================================================
            // 第三步：连续 HELLO
            // ========================================================
            /*
             * ======================================================
             * V24.8.2 核心修复
             * ======================================================
             *
             * HarmonyOS 切网时：
             *
             * netAvailable
             * netCapabilitiesChange
             * netConnectionPropertiesChange
             *
             * 与真正的新网络可发 UDP 的时间可能存在很短延迟。
             *
             * 如果只发送一次：
             *
             * HELLO
             *
             * 很容易刚好发送在切换窗口。
             *
             * 所以这里连续发送 12 次：
             *
             * 150ms × 12
             *
             * 约 1.65 秒。
             *
             * 一旦 VPS 收到其中任何一次：
             *
             * DeviceID
             *      ↓
             * 新 UDP endpoint
             *
             * 后面的音频就开始走新地址。
             */
            const helloCount: number = 5;
            for (let helloIndex: number = 0; helloIndex <
                helloCount; helloIndex++) {
                try {
                    await this.udp.send('WALKIE_HELLO:' +
                        this.deviceId +
                        ':V24.8.3');
                    console.info('WALKIE NET MIGRATION: ' +
                        `HELLO ${helloIndex + 1}/${helloCount}`);
                }
                catch (error) {
                    console.warn('WALKIE NET MIGRATION: ' +
                        'HELLO瞬时失败，继续下一轮');
                }
                if (helloIndex + 1 <
                    helloCount) {
                    await this.sleep(80);
                }
            }
            // ========================================================
            // 第四步：PING
            // ========================================================
            const sequence: number = this.pingSequence;
            this.pingSequence +=
                1;
            const now: number = Date.now();
            this.pendingPingSequence.push(sequence);
            this.pendingPingTime.set(sequence, now);
            try {
                await this.udp.send('WALKIE_NET_PING:' +
                    sequence +
                    ':' +
                    now +
                    ':' +
                    this.deviceId);
            }
            catch (error) {
                console.warn('WALKIE NET MIGRATION: ' +
                    '迁移后PING发送失败');
            }
            // ========================================================
            // 第五步：KEEPALIVE
            // ========================================================
            try {
                await this.udp.send('WALKIE_KEEPALIVE:' +
                    this.deviceId);
            }
            catch (error) {
                console.warn('WALKIE NET MIGRATION: ' +
                    '迁移后KEEPALIVE发送失败');
            }
            // ========================================================
            // 第六步：维持现有连接状态
            // ========================================================
            /*
             * 非常重要：
             *
             * 不执行：
             *
             * connected = false
             *
             * 不执行：
             *
             * playback.stop()
             *
             * 不执行：
             *
             * reconnect()
             *
             * 这样播放链路不会因为切网主动清空。
             */
            this.state.network.quality =
                '良好';
            this.state.message =
                '网络已切换，连接已迁移';
            this.emitState();
            console.info('WALKIE NET MIGRATION: ★迁移流程完成★ ' +
                `elapsed=${Date.now() - startTime}ms`);
        }
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE NET MIGRATION: 迁移失败 ' +
                `code=${businessError.code} ` +
                `message=${businessError.message}`);
            /*
             * 只有真正迁移失败，
             * 才进入原来的自动重连兜底。
             */
            this.connected =
                false;
            this.state.connected =
                false;
            this.state.network.quality =
                '重连中';
            this.state.message =
                '网络切换失败，正在恢复连接…';
            this.emitState();
            this.scheduleAutoReconnect();
        }
        finally {
            this.networkMigrationRunning =
                false;
            this.lastNetworkMigrationTime =
                Date.now();
        }
    }
    // ============================================================
    // 设置持久化 DeviceID
    // ============================================================
    public setDeviceId(deviceId: string): void {
        const clean: string = deviceId.trim();
        if (clean.length ===
            0) {
            return;
        }
        if (this.connected ||
            this.connecting ||
            this.reconnecting ||
            this.autoReconnectLoopRunning) {
            return;
        }
        this.deviceId =
            clean;
        console.info('WALKIE CLIENT: DeviceID=' +
            this.deviceId);
    }
    // ============================================================
    // 获取 DeviceID
    // ============================================================
    public getDeviceId(): string {
        return this.deviceId;
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
        this.connectionGeneration +=
            1;
        /*
         * 用户主动 connect：
         * 取消之前的自动恢复状态。
         */
        this.stopAutoReconnectLoop();
        this.connecting =
            true;
        const clean: string = this.cleanNickname(nickname);
        if (clean.length >
            0) {
            this.state.nickname =
                clean;
        }
        /*
         * 保存当前频道。
         */
        if (this.state.currentChannel.length >
            0) {
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
             * 如果旧 Socket 还存在，
             * 先安全关闭。
             */
            if (this.udp.getBound()) {
                try {
                    await this.udp.close();
                }
                catch {
                    // 忽略
                }
            }
            /*
             * 创建 UDP Socket。
             */
            await this.udp.open(WalkieClient.SERVER_IP, WalkieClient.SERVER_PORT);
            this.lastServerActivityTime =
                Date.now();
            /*
             * HELLO。
             */
            await this.udp.send('WALKIE_HELLO:' +
                this.deviceId +
                ':V24.2');
            /*
             * 登录等待期间持续发送 LOGIN。
             */
            this.startLoginRetry();
            this.startKeepAlive();
            this.startNetworkPing();
            this.state.message =
                '正在建立连接…';
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
                '连接失败，正在自动重试…';
            this.stopTimers();
            this.emitState();
            /*
             * 初次连接失败也不能结束。
             *
             * V24.2：
             * 自动进入持续重连。
             */
            this.scheduleAutoReconnect();
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
        if (this.state.nickname.length >
            0) {
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
            /*
             * 下一轮继续。
             */
        }
    }
    private stopLoginRetry(): void {
        if (this.loginRetryTimer !==
            null) {
            clearInterval(this.loginRetryTimer);
            this.loginRetryTimer =
                null;
        }
    }
    // ============================================================
    // 断开
    // ============================================================
    public async disconnect(): Promise<void> {
        /*
         * 只有这个方法可以真正关闭自动重连。
         */
        this.manualDisconnect =
            true;
        this.connectionGeneration +=
            1;
        this.stopAutoReconnectLoop();
        if (this.networkMigrationTimer !==
            null) {
            clearTimeout(this.networkMigrationTimer);
            this.networkMigrationTimer =
                null;
        }
        this.stopTalkRequestTimer();
        await this.audio.stop();
        await this.playback.stop();
        this.stopTimers();
        this.stopBackgroundRecovery();
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
    // 应用回到前台
    // ============================================================
    public async onForeground(): Promise<void> {
        if (this.manualDisconnect) {
            return;
        }
        this.lastForegroundTime =
            Date.now();
        /*
         * 已连接：
         * 只做数据同步，不重连。
         */
        if (this.connected &&
            this.socketReady()) {
            void this.requestUserList();
            void this.requestChannelList();
            void this.requestChannelMembers();
            return;
        }
        /*
         * 没连接：
         * 不再只尝试一次。
         *
         * 直接进入持续自动重连。
         */
        this.state.message =
            '正在恢复服务器连接…';
        this.state.network.quality =
            '重连中';
        this.emitState();
        this.scheduleAutoReconnect();
    }
    // ============================================================
    // 后台恢复检查
    // ============================================================
    public startBackgroundRecovery(): void {
        this.stopBackgroundRecovery();
        this.backgroundRecoveryTimer =
            setInterval((): void => {
                if (this.manualDisconnect) {
                    return;
                }
                if (this.connected &&
                    this.socketReady()) {
                    return;
                }
                /*
                 * 5秒只是兜底检查。
                 *
                 * 真正网络错误会立即触发
                 * scheduleAutoReconnect()。
                 */
                this.scheduleAutoReconnect();
            }, this.BACKGROUND_RECOVERY_INTERVAL);
    }
    // ============================================================
    // 停止后台恢复
    // ============================================================
    public stopBackgroundRecovery(): void {
        if (this.backgroundRecoveryTimer !==
            null) {
            clearInterval(this.backgroundRecoveryTimer);
            this.backgroundRecoveryTimer =
                null;
        }
    }
    // ============================================================
    // 重新连接
    //
    // V24.2：
    //
    // 单次 reconnect 仍然只负责“尝试一次”。
    //
    // 持续重连由 autoReconnectLoop 负责。
    // ============================================================
    private async reconnect(): Promise<boolean> {
        if (this.manualDisconnect) {
            return false;
        }
        if (this.reconnecting) {
            return false;
        }
        if (this.connected &&
            this.socketReady()) {
            return true;
        }
        this.reconnecting =
            true;
        const generation: number = this.connectionGeneration;
        this.stopLoginRetry();
        this.stopTalkRequestTimer();
        await this.audio.stop();
        await this.playback.stop();
        /*
         * 保存当前频道。
         */
        if (this.state.currentChannel.length >
            0) {
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
            '正在自动恢复连接…';
        this.emitState();
        try {
            /*
             * ========================================================
             * ① 关闭旧 Socket
             * ========================================================
             */
            if (this.udp.getBound()) {
                try {
                    await this.udp.send('WALKIE_GOODBYE');
                }
                catch {
                    /*
                     * 旧 Socket 可能已经不可用。
                     */
                }
            }
            try {
                await this.udp.close();
            }
            catch {
                // 忽略
            }
            /*
             * ========================================================
             * ② 创建全新 Socket
             * ========================================================
             */
            await this.udp.open(WalkieClient.SERVER_IP, WalkieClient.SERVER_PORT);
            this.lastServerActivityTime =
                Date.now();
            /*
             * ========================================================
             * ③ HELLO
             * ========================================================
             */
            await this.udp.send('WALKIE_HELLO:' +
                this.deviceId +
                ':V24.2');
            /*
             * ========================================================
             * ④ LOGIN 重试
             *
             * 先启动。
             * CONNECTED 收到后会自动停止。
             * ========================================================
             */
            this.startLoginRetry();
            /*
             * ========================================================
             * ⑤ KeepAlive / Ping
             * ========================================================
             */
            this.startKeepAlive();
            this.startNetworkPing();
            /*
             * ========================================================
             * ⑥ 等待 CONNECTED
             *
             * V24.2：
             *
             * 3 秒窗口。
             * ========================================================
             */
            const startTime: number = Date.now();
            while (Date.now() -
                startTime <
                WalkieClient.RECONNECT_HANDSHAKE_TIMEOUT) {
                if (generation !==
                    this.connectionGeneration) {
                    return false;
                }
                if (this.connected &&
                    this.socketReady()) {
                    /*
                     * CONNECTED 已到达。
                     */
                    this.stopLoginRetry();
                    this.state.network.quality =
                        '良好';
                    this.state.message =
                        '网络已恢复';
                    this.emitState();
                    /*
                     * 恢复频道。
                     */
                    await this.restoreSavedChannel();
                    /*
                     * 同步列表。
                     */
                    void this.requestUserList();
                    void this.requestChannelList();
                    void this.requestChannelMembers();
                    console.info('WALKIE CLIENT: ' +
                        '★自动重连成功★');
                    return true;
                }
                await this.sleep(50);
            }
            /*
             * ========================================================
             * ⑦ 握手超时
             * ========================================================
             */
            console.warn('WALKIE CLIENT: ' +
                `自动重连握手超时 ${WalkieClient.RECONNECT_HANDSHAKE_TIMEOUT}ms`);
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
        catch (error) {
            const businessError: BusinessError = error as BusinessError;
            console.error('WALKIE CLIENT: ' +
                '自动重连本轮失败 ' +
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
    // V24.2
    //
    // 持续自动重连循环
    //
    // 这是本版本解决“偶尔必须手动重连”的核心。
    // ============================================================
    private scheduleAutoReconnect(): void {
        if (this.manualDisconnect) {
            return;
        }
        if (this.connected &&
            this.socketReady()) {
            return;
        }
        /*
         * 只允许一个自动重连循环。
         */
        if (this.autoReconnectLoopRunning) {
            return;
        }
        console.warn('WALKIE CLIENT: ' +
            '★进入持续自动重连循环★');
        void this.runAutoReconnectLoop();
    }
    // ============================================================
    // V24.2
    //
    // 持续自动重连
    // ============================================================
    private async runAutoReconnectLoop(): Promise<void> {
        if (this.autoReconnectLoopRunning) {
            return;
        }
        this.autoReconnectLoopRunning = true;
        this.connectionGeneration += 1;
        const generation: number = this.connectionGeneration;
        this.autoReconnectAttempt = 0;
        this.autoReconnectDelay =
            WalkieClient.AUTO_RECONNECT_INITIAL_DELAY;
        try {
            while (!this.manualDisconnect &&
                generation === this.connectionGeneration &&
                !this.connected) {
                this.autoReconnectAttempt += 1;
                this.state.network.quality =
                    '重连中';
                this.state.message =
                    '自动重连中… 第' +
                        this.autoReconnectAttempt +
                        '次';
                this.emitState();
                console.info('WALKIE CLIENT: ' +
                    `★自动重连第${this.autoReconnectAttempt}次★ delay=${this.autoReconnectDelay}ms generation=${generation}`);
                const success: boolean = await this.reconnect();
                if (success &&
                    this.connected &&
                    this.socketReady()) {
                    this.autoReconnectAttempt = 0;
                    this.autoReconnectDelay =
                        WalkieClient.AUTO_RECONNECT_INITIAL_DELAY;
                    this.state.network.quality =
                        '良好';
                    this.state.message =
                        '网络已恢复';
                    this.emitState();
                    console.info('WALKIE CLIENT: ★★★★ 自动重连完全成功 ★★★★');
                    break;
                }
                if (this.manualDisconnect ||
                    generation !== this.connectionGeneration) {
                    break;
                }
                this.state.network.quality =
                    '连接中';
                this.state.message =
                    '网络恢复中… ' +
                        `下次重连=${this.autoReconnectDelay}ms`;
                this.emitState();
                await this.sleep(this.autoReconnectDelay);
                this.autoReconnectDelay =
                    Math.min(this.autoReconnectDelay * 2, WalkieClient.AUTO_RECONNECT_MAX_DELAY);
            }
        }
        catch (error) {
            console.error('WALKIE CLIENT: 自动重连循环异常=' +
                JSON.stringify(error));
        }
        finally {
            if (generation === this.connectionGeneration) {
                this.autoReconnectLoopRunning = false;
                this.autoReconnectAttempt = 0;
                this.autoReconnectDelay =
                    WalkieClient.AUTO_RECONNECT_INITIAL_DELAY;
            }
        }
    }
    // ============================================================
    // 停止自动重连
    // ============================================================
    private stopAutoReconnectLoop(): void {
        /*
         * V24.2 不再使用独立 autoReconnectTimer。
         * 自动重连由唯一循环统一管理。
         */
    }
    // ============================================================
    // 抢麦
    // ============================================================
    public async startTalking(): Promise<void> {
        if (!this.connected ||
            !this.socketReady()) {
            this.state.talkStatus =
                'REQUESTING';
            this.state.message =
                '网络已断开，正在重新连接…';
            this.state.network.quality =
                '重连中';
            this.emitState();
            /*
             * 不等待单次 reconnect。
             *
             * 直接启动持续重连。
             */
            this.scheduleAutoReconnect();
            /*
             * 观察一小段时间，
             * 如果很快恢复就继续。
             */
            const startTime: number = Date.now();
            while (Date.now() -
                startTime <
                4000) {
                if (this.connected &&
                    this.socketReady()) {
                    break;
                }
                await this.sleep(100);
            }
            if (!this.connected ||
                !this.socketReady()) {
                this.state.talkStatus =
                    'NONE';
                this.state.message =
                    '网络正在自动恢复，请稍后再抢麦';
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
            await this.udp.send('WALKIE_TALK_START');
            this.talkRequestTimer =
                setTimeout((): void => {
                    this.talkRequestTimer =
                        null;
                    if (this.state.talkStatus !==
                        'REQUESTING') {
                        return;
                    }
                    this.state.message =
                        '抢麦响应超时，正在检查网络…';
                    this.state.network.quality =
                        '检测中';
                    this.emitState();
                    this.state.talkStatus =
                        'NONE';
                    this.emitState();
                    /*
                     * 抢麦超时不立即摧毁连接。
                     *
                     * 先启动自动恢复检查。
                     */
                    this.scheduleAutoReconnect();
                }, WalkieClient.TALK_REQUEST_TIMEOUT) as number;
        }
        catch {
            this.stopTalkRequestTimer();
            this.state.talkStatus =
                'NONE';
            this.state.message =
                '抢麦发送失败，正在恢复网络…';
            this.emitState();
            this.scheduleAutoReconnect();
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
        if (channel.length ===
            0 ||
            channel ===
                'public') {
            return;
        }
        if (!this.connected ||
            !this.udp.getBound()) {
            return;
        }
        try {
            let message: string = 'WALKIE_JOIN_CHANNEL:' +
                channel;
            if (this.savedChannelPassword.length >
                0) {
                message =
                    message +
                        ':' +
                        this.savedChannelPassword;
            }
            this.restoringChannel =
                true;
            await this.udp.send(message);
            console.info('WALKIE CLIENT: ' +
                `正在恢复频道=${channel}`);
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
        if (clean.length ===
            0) {
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
        if (cleanChannel.length ===
            0) {
            return;
        }
        /*
         * 保存频道，
         * 以后自动重连使用。
         */
        this.savedChannelName =
            cleanChannel;
        this.savedChannelPassword =
            password.trim();
        let message: string = 'WALKIE_JOIN_CHANNEL:' +
            cleanChannel;
        if (password.trim().length >
            0) {
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
            this.scheduleAutoReconnect();
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
        if (cleanName.length ===
            0) {
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
        if (cleanChannel.length ===
            0 ||
            cleanChannel ===
                'public') {
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
        /*
         * 网络错误：
         *
         * 停止业务状态，
         * 但不要停止自动重连。
         */
        this.connected =
            false;
        this.state.connected =
            false;
        this.stopTalkRequestTimer();
        void this.audio.stop();
        void this.playback.stop();
        this.state.talkStatus =
            'NONE';
        this.state.network.quality =
            '网络异常';
        this.state.message =
            message;
        this.emitState();
        /*
         * 立即启动持续重连。
         */
        this.scheduleAutoReconnect();
    }
    // ============================================================
    // UDP 消息
    // ============================================================
    private handleUdpMessage(message: WalkieUdpMessage): void {
        this.lastServerActivityTime =
            Date.now();
        let text: string = '';
        /*
         * ========================================================
         * Binary
         * ========================================================
         */
        if (typeof message.data !==
            'string') {
            const isW23A: boolean = WalkieAudioPacket.isW23A(message.data);
            if (isW23A) {
                console.info('WALKIE RX AUDIO W23A: ' +
                    `length=${message.data.byteLength}`);
                void this.playback
                    .playPacket(message.data);
                return;
            }
            text =
                this.decodeText(message.data);
            if (text.indexOf('WALKIE_') ===
                0) {
                console.info('WALKIE RX BINARY TEXT:', text);
            }
            else {
                console.info('WALKIE RX AUDIO LEGACY: ' +
                    `length=${message.data.byteLength}`);
                void this.playback
                    .playPacket(message.data);
                return;
            }
        }
        else {
            text =
                this.decodeText(message.data);
        }
        if (text.length ===
            0) {
            return;
        }
        console.info('WALKIE RX:', text);
        // ==========================================================
        // CONNECTED
        // ==========================================================
        if (text ===
            'WALKIE_CONNECTED') {
            /*
             * 连接正式建立。
             */
            this.connected =
                true;
            this.state.connected =
                true;
            this.lastServerActivityTime =
                Date.now();
            this.pendingPingSequence =
                [];
            this.pendingPingTime.clear();
            this.pingResults =
                [];
            /*
             * 登录重试停止。
             */
            this.stopLoginRetry();
            /*
             * 自动重连延迟恢复最快。
             */
            this.autoReconnectAttempt =
                0;
            this.autoReconnectDelay =
                WalkieClient.AUTO_RECONNECT_INITIAL_DELAY;
            this.state.message =
                '服务器连接正常';
            this.state.network.quality =
                '良好';
            this.emitState();
            /*
             * CONNECTED 后 LOGIN。
             */
            void this.sendLoginAfterConnected();
            return;
        }
        // ==========================================================
        // KEEPALIVE
        // ==========================================================
        if (text ===
            'WALKIE_KEEPALIVE') {
            this.lastServerActivityTime =
                Date.now();
            return;
        }
        // ==========================================================
        // USER_OK
        // ==========================================================
        if (text.indexOf('WALKIE_USER_OK:') ===
            0) {
            this.handleUserOk(text);
            return;
        }
        // ==========================================================
        // USER_STATUS
        // ==========================================================
        if (text.indexOf('WALKIE_USER_STATUS:') ===
            0) {
            this.handleUserStatus(text);
            return;
        }
        // ==========================================================
        // USER_LIST
        // ==========================================================
        if (text.indexOf('WALKIE_USER_LIST:') ===
            0) {
            this.handleUserList(text);
            return;
        }
        // ==========================================================
        // CHANNEL_MEMBERS
        // ==========================================================
        if (text.indexOf('WALKIE_CHANNEL_MEMBERS:') ===
            0) {
            this.handleChannelMembers(text);
            return;
        }
        // ==========================================================
        // CHANNEL_LIST
        // ==========================================================
        if (text.indexOf('WALKIE_CHANNEL_LIST:') ===
            0) {
            this.handleChannelList(text);
            return;
        }
        // ==========================================================
        // CHANNEL_INFO
        // ==========================================================
        if (text.indexOf('WALKIE_CHANNEL_INFO:') ===
            0) {
            this.handleChannelInfo(text);
            return;
        }
        // ==========================================================
        // CHANNEL_JOINED
        // ==========================================================
        if (text.indexOf('WALKIE_CHANNEL_JOINED:') ===
            0) {
            this.handleChannelJoined(text);
            return;
        }
        // ==========================================================
        // CHANNEL_ERROR
        // ==========================================================
        if (text.indexOf('WALKIE_CHANNEL_ERROR:') ===
            0) {
            this.restoringChannel =
                false;
            this.state.message =
                text.substring('WALKIE_CHANNEL_ERROR:'.length);
            this.emitState();
            return;
        }
        // ==========================================================
        // CHANNEL_DELETED
        // ==========================================================
        if (text.indexOf('WALKIE_CHANNEL_DELETED:') ===
            0) {
            this.handleChannelDeleted(text);
            return;
        }
        // ==========================================================
        // TALK OK
        // ==========================================================
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
        // ==========================================================
        // TALK BUSY
        // ==========================================================
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
        // ==========================================================
        // TALK RELEASED
        // ==========================================================
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
        // ==========================================================
        // TALKING
        // ==========================================================
        if (text.indexOf('WALKIE_TALKING:') ===
            0) {
            this.state.message =
                text;
            this.emitState();
            return;
        }
        // ==========================================================
        // NET PONG
        // ==========================================================
        if (text.indexOf('WALKIE_NET_PONG:') ===
            0) {
            this.handleNetworkPong(text);
            return;
        }
    }
    // ============================================================
    // CONNECTED 后 LOGIN
    // ============================================================
    private async sendLoginAfterConnected(): Promise<void> {
        try {
            await this.sendLogin();
            /*
             * 恢复频道。
             */
            await this.restoreSavedChannel();
            /*
             * 数据同步。
             */
            void this.requestUserList();
            void this.requestChannelList();
            void this.requestChannelMembers();
        }
        catch {
            /*
             * CONNECTED 后如果 LOGIN 发送失败，
             * 不立即销毁整个连接。
             *
             * LoginRetry 会继续尝试。
             */
            this.startLoginRetry();
        }
    }
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
        if (parts.length >
            0) {
            const id: string = parts[0].trim();
            if (id.length >
                0) {
                this.state.userId =
                    id;
            }
        }
        if (parts.length >
            1) {
            const name: string = parts[1].trim();
            if (name.length >
                0) {
                this.state.nickname =
                    name;
            }
        }
        if (parts.length >
            2) {
            const channel: string = parts[2].trim();
            if (channel.length >
                0 &&
                !this.restoringChannel) {
                this.state.currentChannel =
                    channel;
                this.savedChannelName =
                    channel;
            }
        }
        void this.requestUserList();
        void this.requestChannelMembers();
        this.emitState();
    }
    // ============================================================
    // USER_STATUS
    // ============================================================
    private handleUserStatus(text: string): void {
        const payload: string = text.substring('WALKIE_USER_STATUS:'.length);
        const parts: string[] = payload.split(':');
        if (parts.length >
            0) {
            const id: string = parts[0].trim();
            if (id.length >
                0) {
                this.state.userId =
                    id;
            }
        }
        if (parts.length >
            1) {
            const name: string = parts[1].trim();
            if (name.length >
                0) {
                this.state.nickname =
                    name;
            }
        }
        if (parts.length >
            4) {
            const channel: string = parts[4].trim();
            if (channel.length >
                0 &&
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
        if (separator <
            0) {
            return;
        }
        const channel: string = payload
            .substring(0, separator)
            .trim();
        const members: string = payload.substring(separator + 1);
        this.state.onlineUsers =
            this.parseUsers(members);
        if (channel.length >
            0 &&
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
        if (separator <
            0) {
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
        if (channel.length >
            0 &&
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
        if (payload.trim().length ===
            0) {
            return;
        }
        const result: WalkieChannel[] = [];
        const items: string[] = payload.split(';');
        for (let index: number = 0; index <
            items.length; index++) {
            const item: string = items[index];
            const parts: string[] = item.split(',');
            let name: string = '';
            if (parts.length >
                0) {
                name =
                    parts[0].trim();
            }
            if (name.length ===
                0) {
                continue;
            }
            let type: string = 'PUBLIC';
            if (parts.length >
                1) {
                type =
                    parts[1]
                        .trim()
                        .toUpperCase();
            }
            let count: number = 0;
            if (parts.length >
                2) {
                const parsed: number = Number.parseInt(parts[2].trim(), 10);
                if (!Number.isNaN(parsed)) {
                    count =
                        Math.max(0, parsed);
                }
            }
            result.push({
                name: name,
                onlineCount: count,
                isPrivate: type ===
                    'PRIVATE',
                requirePassword: type ===
                    'PRIVATE'
            });
        }
        if (result.length >
            0) {
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
        if (parts.length >
            0) {
            name =
                parts[0].trim();
        }
        if (parts.length >
            1) {
            type =
                parts[1]
                    .trim()
                    .toUpperCase();
        }
        if (name.length >
            0) {
            this.state.currentChannel =
                name;
            this.state.currentChannelPrivate =
                type ===
                    'PRIVATE';
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
        if (parts.length >
            0) {
            channel =
                parts[0].trim();
        }
        if (channel.length >
            0) {
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
        for (let index: number = 0; index <
            this.state.channels.length; index++) {
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
        if (parts.length <
            2) {
            return;
        }
        const sequence: number = Number.parseInt(parts[0].trim(), 10);
        if (Number.isNaN(sequence)) {
            return;
        }
        const sentTime: number | undefined = this.pendingPingTime.get(sequence);
        if (sentTime ===
            undefined) {
            return;
        }
        this.pendingPingTime.delete(sequence);
        this.removePendingSequence(sequence);
        const latency: number = Math.max(0, Date.now() - sentTime);
        this.lastServerActivityTime =
            Date.now();
        this.addPingResult(true);
        this.state.network.latency =
            latency;
        this.state.network.loss =
            this.calculatePingLoss();
        /*
         * Android V23.1 风格 jitter：
         * 使用相邻 Ping 延迟变化衡量抖动。
         * 使用平滑处理，避免一次尖峰立即改变播放策略。
         */
        if (this.lastMeasuredLatency >= 0) {
            const instantJitter: number = Math.abs(latency -
                this.lastMeasuredLatency);
            if (this.state.network.jitter < 0) {
                this.state.network.jitter =
                    instantJitter;
            }
            else {
                this.state.network.jitter =
                    Math.round(this.state.network.jitter * 0.75 +
                        instantJitter * 0.25);
            }
        }
        else {
            this.state.network.jitter =
                0;
        }
        this.lastMeasuredLatency =
            latency;
        this.updateNetworkStatus();
        this.updatePlaybackNetworkQuality();
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
        if (this.keepAliveTimer !==
            null) {
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
            await this.udp.send('WALKIE_KEEPALIVE:' +
                this.deviceId);
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
                '心跳发送失败，正在自动重连…';
            this.emitState();
            this.scheduleAutoReconnect();
        }
    }
    // ============================================================
    // Network Ping
    // ============================================================
    private startNetworkPing(): void {
        this.stopNetworkPing();
        this.pingResults = [];
        this.networkPingTimer =
            setInterval((): void => {
                void this.sendNetworkPing();
            }, WalkieClient.NETWORK_PING_INTERVAL);
        void this.sendNetworkPing();
    }
    private stopNetworkPing(): void {
        if (this.networkPingTimer !== null) {
            clearInterval(this.networkPingTimer);
            this.networkPingTimer = null;
        }
        this.pendingPingSequence = [];
        this.pendingPingTime.clear();
        this.pingResults = [];
        this.lastMeasuredLatency = -1;
        this.state.network.jitter = -1;
        this.updatePlaybackNetworkQuality();
    }
    private async sendNetworkPing(): Promise<void> {
        if (!this.connected ||
            !this.udp.getBound()) {
            return;
        }
        const now: number = Date.now();
        /*
         * Ping 只做网络质量统计。
         * 不因为 Ping 超时主动断线。
         */
        this.expirePingStatistics(now);
        while (this.pendingPingSequence.length >=
            WalkieClient.NETWORK_PING_WINDOW) {
            const first: number = this.pendingPingSequence[0];
            this.pendingPingSequence.splice(0, 1);
            this.pendingPingTime.delete(first);
        }
        const sequence: number = this.pingSequence;
        this.pingSequence += 1;
        this.pendingPingSequence.push(sequence);
        this.pendingPingTime.set(sequence, now);
        try {
            await this.udp.send('WALKIE_NET_PING:' +
                sequence +
                ':' +
                now +
                ':' +
                this.deviceId);
        }
        catch {
            this.pendingPingTime.delete(sequence);
            this.removePendingSequence(sequence);
            this.addPingResult(false);
            this.updateNetworkStatus();
        }
    }
    // ============================================================
    private addPingResult(success: boolean): void {
        this.pingResults.push(success);
        while (this.pingResults.length >
            WalkieClient.NETWORK_PING_WINDOW) {
            this.pingResults.shift();
        }
    }
    private calculatePingLoss(): number {
        if (this.pingResults.length === 0) {
            return 0;
        }
        let lost: number = 0;
        for (let index: number = 0; index < this.pingResults.length; index++) {
            if (!this.pingResults[index]) {
                lost += 1;
            }
        }
        return Math.round(lost * 100 / this.pingResults.length);
    }
    private expirePingStatistics(now: number): void {
        const expired: number[] = [];
        for (let index: number = 0; index < this.pendingPingSequence.length; index++) {
            const sequence: number = this.pendingPingSequence[index];
            const sentTime: number | undefined = this.pendingPingTime.get(sequence);
            if (sentTime !== undefined &&
                now - sentTime >= WalkieClient.NETWORK_PING_TIMEOUT) {
                expired.push(sequence);
            }
        }
        for (let index: number = 0; index < expired.length; index++) {
            const sequence: number = expired[index];
            this.pendingPingTime.delete(sequence);
            this.removePendingSequence(sequence);
            this.addPingResult(false);
        }
        if (expired.length > 0) {
            this.updateNetworkStatus();
        }
    }
    private updatePlaybackNetworkQuality(): void {
        this.playback.setNetworkQuality(this.state.network.latency, this.state.network.loss, this.state.network.jitter);
    }
    private updateNetworkStatus(): void {
        this.state.network.loss =
            this.calculatePingLoss();
        const latency: number = this.state.network.latency;
        const loss: number = this.state.network.loss;
        const jitter: number = this.state.network.jitter;
        if (latency < 0) {
            this.state.network.quality =
                '检测中';
        }
        else if (loss >= 20 ||
            latency >= 300 ||
            jitter >= 100) {
            this.state.network.quality =
                '较差';
        }
        else if (loss >= 8 ||
            latency >= 180 ||
            jitter >= 50) {
            this.state.network.quality =
                '一般';
        }
        else if (loss >= 3 ||
            latency >= 100 ||
            jitter >= 25) {
            this.state.network.quality =
                '良好';
        }
        else {
            this.state.network.quality =
                '优秀';
        }
        this.updatePlaybackNetworkQuality();
    }
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
        if (this.talkRequestTimer !==
            null) {
            clearTimeout(this.talkRequestTimer);
            this.talkRequestTimer =
                null;
        }
    }
    // ============================================================
    // 删除 Pending Ping
    // ============================================================
    private removePendingSequence(sequence: number): void {
        const result: number[] = [];
        for (let index: number = 0; index <
            this.pendingPingSequence.length; index++) {
            const item: number = this.pendingPingSequence[index];
            if (item !==
                sequence) {
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
        if (clean.length ===
            0) {
            return result;
        }
        const items: string[] = clean.split(';');
        for (let index: number = 0; index <
            items.length; index++) {
            const item: string = items[index];
            const separator: number = item.indexOf('|');
            if (separator <=
                0) {
                continue;
            }
            const userId: string = item
                .substring(0, separator)
                .trim();
            const nickname: string = item
                .substring(separator + 1)
                .trim();
            if (userId.length ===
                0) {
                continue;
            }
            result.push({
                userId: userId,
                nickname: nickname.length >
                    0
                    ? nickname
                    : '未命名用户'
            });
        }
        /*
         * 去重。
         */
        const unique: WalkieUser[] = [];
        for (let index: number = 0; index <
            result.length; index++) {
            const user: WalkieUser = result[index];
            let exists: boolean = false;
            for (let inner: number = 0; inner <
                unique.length; inner++) {
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
        if (typeof data ===
            'string') {
            return data.trim();
        }
        try {
            const bytes: Uint8Array = new Uint8Array(data);
            if (bytes.length ===
                0) {
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
        return new Promise<void>((resolve: () => void): void => {
            setTimeout(resolve, milliseconds);
        });
    }
    // ============================================================
    // 状态通知
    // ============================================================
    private emitState(): void {
        const callback: WalkieStateCallback | null = this.stateCallback;
        if (callback !==
            null) {
            callback(this.getState());
        }
    }
}
