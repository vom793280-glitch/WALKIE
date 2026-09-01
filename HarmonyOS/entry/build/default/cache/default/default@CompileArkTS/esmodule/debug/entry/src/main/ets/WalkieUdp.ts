import socket from "@ohos:net.socket";
export interface WalkieUdpMessage {
    data: string | ArrayBuffer;
    remoteAddress: string;
    remotePort: number;
}
export class WalkieUdp {
    private udpSocket: socket.UDPSocket | null = null;
    private serverIp: string = '';
    private serverPort: number = 0;
    private isBound: boolean = false;
    private messageCallback: ((message: WalkieUdpMessage) => void) | null = null;
    private errorCallback: ((message: string) => void) | null = null;
    // ============================================================
    // 消息回调
    // ============================================================
    public setMessageCallback(callback: (message: WalkieUdpMessage) => void): void {
        this.messageCallback =
            callback;
    }
    // ============================================================
    // 错误回调
    // ============================================================
    public setErrorCallback(callback: (message: string) => void): void {
        this.errorCallback =
            callback;
    }
    // ============================================================
    // 打开 UDP
    // ============================================================
    public async open(serverIp: string, serverPort: number): Promise<void> {
        await this.close();
        this.serverIp =
            serverIp;
        this.serverPort =
            serverPort;
        const udp: socket.UDPSocket = socket.constructUDPSocketInstance();
        this.udpSocket =
            udp;
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
                // ====================================================
                // UDP 收包诊断
                //
                // 文本控制消息：
                // WALKIE_CONNECTED
                // WALKIE_USER_OK
                // WALKIE_USER_LIST
                // ...
                //
                // 二进制音频：
                // W23A + StreamID + Sequence + Opus
                // ====================================================
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
                // ====================================================
                // 转交 WalkieClient
                // ====================================================
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
        // UDP 错误
        // ==========================================================
        udp.on('error', (error: Error): void => {
            this.emitError('UDP错误：' +
                error.message);
        });
        // ==========================================================
        // 绑定随机端口
        // ==========================================================
        await udp.bind({
            address: '0.0.0.0',
            port: 0
        });
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
        const udp: socket.UDPSocket | null = this.udpSocket;
        this.udpSocket =
            null;
        this.isBound =
            false;
        if (udp === null) {
            return;
        }
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
