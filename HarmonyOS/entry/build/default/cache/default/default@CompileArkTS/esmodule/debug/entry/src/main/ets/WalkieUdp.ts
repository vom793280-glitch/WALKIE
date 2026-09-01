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
    // Socket 生命周期编号
    //
    // 每创建一个新的 UDP Socket：
    //
    // generation + 1
    //
    // 旧 Socket 即使晚一点触发 message/error，
    // 也不能再影响新的 Socket。
    // ============================================================
    private socketGeneration: number = 0;
    // ============================================================
    // 消息回调
    // ============================================================
    private messageCallback: ((message: WalkieUdpMessage) => void) | null = null;
    // ============================================================
    // 错误回调
    // ============================================================
    private errorCallback: ((message: string) => void) | null = null;
    // ============================================================
    // 消息回调注册
    // ============================================================
    public setMessageCallback(callback: (message: WalkieUdpMessage) => void): void {
        this.messageCallback =
            callback;
    }
    // ============================================================
    // 错误回调注册
    // ============================================================
    public setErrorCallback(callback: (message: string) => void): void {
        this.errorCallback =
            callback;
    }
    // ============================================================
    // 打开 UDP
    // ============================================================
    public async open(serverIp: string, serverPort: number): Promise<void> {
        /*
         * ==========================================================
         * 先彻底关闭旧 Socket
         * ==========================================================
         */
        await this.close();
        /*
         * ==========================================================
         * 新 Socket 生命周期编号
         * ==========================================================
         */
        this.socketGeneration +=
            1;
        const generation: number = this.socketGeneration;
        this.serverIp =
            serverIp;
        this.serverPort =
            serverPort;
        const udp: socket.UDPSocket = socket.constructUDPSocketInstance();
        /*
         * 只有当前代 Socket 才能成为 active Socket。
         */
        this.udpSocket =
            udp;
        // ==========================================================
        // UDP 消息
        // ==========================================================
        udp.on('message', (value: socket.SocketMessageInfo): void => {
            /*
             * ========================================================
             * 防止旧 Socket 回调污染新 Socket
             * ========================================================
             */
            if (generation !==
                this.socketGeneration) {
                return;
            }
            if (this.udpSocket !==
                udp) {
                return;
            }
            if (!this.isBound) {
                return;
            }
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
                /*
                 * ======================================================
                 * UDP 收包诊断
                 * ======================================================
                 */
                if (typeof message ===
                    'string') {
                    console.info('WALKIE UDP RX TEXT: ' +
                        message);
                }
                else {
                    const bytes: Uint8Array = new Uint8Array(message);
                    let magic: string = '';
                    if (bytes.length >=
                        4) {
                        magic =
                            String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]);
                    }
                    console.info('WALKIE UDP RX BINARY: ' +
                        `length=${bytes.length} ` +
                        `magic=${magic}`);
                }
                /*
                 * ======================================================
                 * 转交 WalkieClient
                 * ======================================================
                 */
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
                /*
                 * 当前 Socket 才能上报错误。
                 */
                if (generation !==
                    this.socketGeneration) {
                    return;
                }
                this.emitError('UDP消息处理异常：' +
                    JSON.stringify(error));
            }
        });
        // ==========================================================
        // UDP 错误
        // ==========================================================
        udp.on('error', (error: Error): void => {
            /*
             * 忽略旧 Socket 的迟到错误。
             */
            if (generation !==
                this.socketGeneration) {
                return;
            }
            if (this.udpSocket !==
                udp) {
                return;
            }
            this.emitError('UDP错误：' +
                error.message);
        });
        // ==========================================================
        // 绑定随机端口
        // ==========================================================
        try {
            await udp.bind({
                address: '0.0.0.0',
                port: 0
            });
        }
        catch (error) {
            /*
             * 绑定失败时，如果这个 Socket 仍然是当前 Socket，
             * 清理当前状态。
             */
            if (this.udpSocket ===
                udp) {
                this.udpSocket =
                    null;
                this.isBound =
                    false;
            }
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
            throw new Error('UDP bind 失败');
        }
        /*
         * bind 成功以后，再次确认这个 Socket 仍然是当前 Socket。
         *
         * 防止极端情况下：
         *
         * open A
         * ↓
         * open B
         * ↓
         * A bind 晚完成
         */
        if (generation !==
            this.socketGeneration ||
            this.udpSocket !==
                udp) {
            try {
                await udp.close();
            }
            catch {
                // 忽略
            }
            return;
        }
        this.isBound =
            true;
        console.info('WALKIE UDP: Socket 已绑定');
    }
    // ============================================================
    // 发送 UDP
    // ============================================================
    public async send(data: string | ArrayBuffer): Promise<void> {
        const udp: socket.UDPSocket | null = this.udpSocket;
        if (udp === null ||
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
    // 关闭 UDP
    // ============================================================
    public async close(): Promise<void> {
        /*
         * ==========================================================
         * 先让当前 Socket 立即失效
         *
         * 这样即使旧 Socket 稍后还有回调，
         * 也会因为 generation 不匹配而被忽略。
         * ==========================================================
         */
        this.socketGeneration +=
            1;
        const udp: socket.UDPSocket | null = this.udpSocket;
        this.udpSocket =
            null;
        this.isBound =
            false;
        if (udp === null) {
            return;
        }
        // ==========================================================
        // 取消消息监听
        // ==========================================================
        try {
            udp.off('message');
        }
        catch (error) {
            this.emitError('UDP取消消息监听异常：' +
                JSON.stringify(error));
        }
        // ==========================================================
        // 取消错误监听
        // ==========================================================
        try {
            udp.off('error');
        }
        catch (error) {
            this.emitError('UDP取消错误监听异常：' +
                JSON.stringify(error));
        }
        // ==========================================================
        // 真正关闭
        // ==========================================================
        try {
            await udp.close();
        }
        catch (error) {
            /*
             * close 失败不再让旧 Socket 继续成为当前连接。
             */
            this.emitError('UDP关闭异常：' +
                JSON.stringify(error));
        }
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
    // 获取 Socket 状态
    // ============================================================
    public getBound(): boolean {
        return this.isBound;
    }
    // ============================================================
    // 错误通知
    // ============================================================
    private emitError(message: string): void {
        const callback = this.errorCallback;
        if (callback !==
            null) {
            callback(message);
        }
    }
}
