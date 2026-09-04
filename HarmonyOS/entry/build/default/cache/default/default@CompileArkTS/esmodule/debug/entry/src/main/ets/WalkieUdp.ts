import socket from "@ohos:net.socket";
export interface WalkieUdpMessage {
    data: string | ArrayBuffer;
    remoteAddress: string;
    remotePort: number;
}
export class WalkieUdp {
    // ============================================================
    // 当前 UDP Socket
    // ============================================================
    private udpSocket: socket.UDPSocket | null = null;
    private serverIp: string = '';
    private serverPort: number = 0;
    private isBound: boolean = false;
    // ============================================================
    // 无缝迁移旧 Socket
    // ============================================================
    private handoffCloseTimer: number | null = null;
    private handoffOldSocket: socket.UDPSocket | null = null;
    // ============================================================
    // 回调
    // ============================================================
    private messageCallback: ((message: WalkieUdpMessage) => void) | null = null;
    private errorCallback: ((message: string) => void) | null = null;
    // ============================================================
    // 注册消息回调
    // ============================================================
    public setMessageCallback(callback: (message: WalkieUdpMessage) => void): void {
        this.messageCallback =
            callback;
    }
    // ============================================================
    // 注册错误回调
    // ============================================================
    public setErrorCallback(callback: (message: string) => void): void {
        this.errorCallback =
            callback;
    }
    // ============================================================
    // 普通打开
    // ============================================================
    public async open(serverIp: string, serverPort: number): Promise<void> {
        /*
         * 普通首次连接 / 普通重连：
         *
         * 仍然保持：
         *
         * 关闭旧 Socket
         *      ↓
         * 创建新 Socket
         *
         * 不改变原有重连逻辑。
         */
        await this.close();
        this.serverIp =
            serverIp;
        this.serverPort =
            serverPort;
        const udp: socket.UDPSocket = socket.constructUDPSocketInstance();
        await this.prepareSocket(udp);
        this.udpSocket =
            udp;
        this.isBound =
            true;
        console.info('WALKIE UDP: 普通 Socket 创建成功');
    }
    // ============================================================
    // V24.8.1 无缝网络迁移
    // ============================================================
    /*
     * 核心原则：
     *
     * 1. 绝不先关闭当前 Socket
     * 2. 先创建新 Socket
     * 3. 新 Socket bind 成功以后才切换
     * 4. 旧 Socket 延迟关闭
     *
     * 这样网络切换期间：
     *
     *     old socket
     *          +
     *     new socket
     *          ↓
     *       短暂重叠
     *
     * 防止客户端自己制造硬断。
     */
    public async migrate(serverIp: string, serverPort: number, oldSocketGraceMs: number = 3000): Promise<void> {
        const previousSocket: socket.UDPSocket | null = this.udpSocket;
        const previousBound: boolean = this.isBound;
        this.serverIp =
            serverIp;
        this.serverPort =
            serverPort;
        /*
         * 如果上一次迁移还有遗留旧 Socket，
         * 先回收掉。
         */
        await this.finishPendingHandoff();
        /*
         * 创建新 Socket。
         */
        const newSocket: socket.UDPSocket = socket.constructUDPSocketInstance();
        try {
            /*
             * 新 Socket 必须先成功 bind。
             *
             * 如果新 Socket 没有成功，
             * 完全不碰旧 Socket。
             */
            await this.prepareSocket(newSocket);
        }
        catch (error) {
            try {
                await newSocket.close();
            }
            catch {
                // 忽略
            }
            /*
             * 恢复旧 Socket。
             */
            if (previousSocket !==
                null &&
                previousBound) {
                this.udpSocket =
                    previousSocket;
                this.isBound =
                    true;
            }
            if (error instanceof Error) {
                throw error;
            }
            else {
                throw new Error('UDP迁移 Socket 创建失败');
            }
        }
        /*
         * ========================================================
         * 新 Socket 已经准备完成
         * ========================================================
         *
         * 从这里开始：
         *
         * send()
         *
         * 会自动走新 Socket。
         */
        this.udpSocket =
            newSocket;
        this.isBound =
            true;
        console.info('WALKIE UDP: ★新 Socket bind 成功，开始无缝切换★');
        /*
         * ========================================================
         * 旧 Socket 延迟关闭
         * ========================================================
         */
        if (previousSocket !==
            null &&
            previousSocket !==
                newSocket) {
            this.handoffOldSocket =
                previousSocket;
            this.handoffCloseTimer =
                setTimeout((): void => {
                    const oldSocket: socket.UDPSocket | null = this.handoffOldSocket;
                    this.handoffOldSocket =
                        null;
                    this.handoffCloseTimer =
                        null;
                    if (oldSocket !==
                        null) {
                        void this.closeSocket(oldSocket);
                        console.info('WALKIE UDP: ★旧 Socket 延迟关闭★');
                    }
                }, Math.max(1000, oldSocketGraceMs)) as number;
        }
    }
    // ============================================================
    // 发送
    // ============================================================
    public async send(data: string | ArrayBuffer): Promise<void> {
        const udp: socket.UDPSocket | null = this.udpSocket;
        if (udp ===
            null ||
            !this.isBound) {
            throw new Error('UDP Socket 未打开');
        }
        await udp.send({
            data: data,
            address: {
                address: this.serverIp,
                port: this.serverPort
            }
        });
    }
    // ============================================================
    // 关闭
    // ============================================================
    public async close(): Promise<void> {
        await this.finishPendingHandoff();
        const udp: socket.UDPSocket | null = this.udpSocket;
        this.udpSocket =
            null;
        this.isBound =
            false;
        if (udp ===
            null) {
            return;
        }
        await this.closeSocket(udp);
        console.info('WALKIE UDP: Socket 已关闭');
    }
    // ============================================================
    // 获取服务器 IP
    // ============================================================
    public getServerIp(): string {
        return this.serverIp;
    }
    // ============================================================
    // 获取服务器端口
    // ============================================================
    public getServerPort(): number {
        return this.serverPort;
    }
    // ============================================================
    // 获取绑定状态
    // ============================================================
    public getBound(): boolean {
        return this.isBound;
    }
    // ============================================================
    // Socket 准备
    // ============================================================
    private async prepareSocket(udp: socket.UDPSocket): Promise<void> {
        // ==========================================================
        // 消息
        // ==========================================================
        udp.on('message', (value: socket.SocketMessageInfo): void => {
            try {
                const remoteInfo = value.remoteInfo;
                let remoteAddress: string = '';
                let remotePort: number = 0;
                if (remoteInfo !==
                    undefined) {
                    remoteAddress =
                        remoteInfo.address;
                    remotePort =
                        remoteInfo.port;
                }
                const message: string | ArrayBuffer = value.message;
                const callback = this.messageCallback;
                if (callback !==
                    null) {
                    const result: WalkieUdpMessage = {
                        data: message,
                        remoteAddress: remoteAddress,
                        remotePort: remotePort
                    };
                    callback(result);
                }
            }
            catch (error) {
                this.emitError('UDP消息处理异常：' +
                    JSON.stringify(error));
            }
        });
        // ==========================================================
        // 错误
        // ==========================================================
        udp.on('error', (error: Error): void => {
            this.emitError('UDP错误：' +
                error.message);
        });
        // ==========================================================
        // bind
        // ==========================================================
        try {
            await udp.bind({
                address: '0.0.0.0',
                port: 0
            });
        }
        catch (error) {
            try {
                udp.off('message');
            }
            catch {
                // 忽略
            }
            try {
                udp.off('error');
            }
            catch {
                // 忽略
            }
            try {
                await udp.close();
            }
            catch {
                // 忽略
            }
            if (error instanceof Error) {
                throw error;
            }
            else {
                throw new Error('UDP bind 失败');
            }
        }
        console.info('WALKIE UDP: bind 成功');
    }
    // ============================================================
    // 结束旧 Socket 迁移
    // ============================================================
    private async finishPendingHandoff(): Promise<void> {
        if (this.handoffCloseTimer !==
            null) {
            clearTimeout(this.handoffCloseTimer);
            this.handoffCloseTimer =
                null;
        }
        const oldSocket: socket.UDPSocket | null = this.handoffOldSocket;
        this.handoffOldSocket =
            null;
        if (oldSocket !==
            null) {
            await this.closeSocket(oldSocket);
        }
    }
    // ============================================================
    // 真正关闭指定 Socket
    // ============================================================
    private async closeSocket(udp: socket.UDPSocket): Promise<void> {
        try {
            udp.off('message');
        }
        catch (error) {
            this.emitError('UDP取消消息监听异常：' +
                JSON.stringify(error));
        }
        try {
            udp.off('error');
        }
        catch (error) {
            this.emitError('UDP取消错误监听异常：' +
                JSON.stringify(error));
        }
        try {
            await udp.close();
        }
        catch (error) {
            this.emitError('UDP关闭异常：' +
                JSON.stringify(error));
        }
    }
    // ============================================================
    // 错误回调
    // ============================================================
    private emitError(message: string): void {
        const callback = this.errorCallback;
        if (callback !==
            null) {
            callback(message);
        }
    }
}
