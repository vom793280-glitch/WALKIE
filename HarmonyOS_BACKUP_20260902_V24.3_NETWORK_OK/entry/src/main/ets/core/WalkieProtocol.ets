export class WalkieProtocol {
  // ============================================================
  // 基础
  // ============================================================

  static readonly HELLO: string = 'WALKIE_HELLO'
  static readonly CONNECTED: string = 'WALKIE_CONNECTED'
  static readonly KEEPALIVE: string = 'WALKIE_KEEPALIVE'
  static readonly GOODBYE: string = 'WALKIE_GOODBYE'

  // ============================================================
  // 登录/用户
  // ============================================================

  static readonly LOGIN: string = 'WALKIE_LOGIN'
  static readonly USER_OK: string = 'WALKIE_USER_OK'
  static readonly USER_STATUS: string = 'WALKIE_USER_STATUS'
  static readonly SET_NICKNAME: string = 'WALKIE_SET_NICKNAME'
  static readonly USER_LIST: string = 'WALKIE_USER_LIST'

  // ============================================================
  // 抢麦
  // ============================================================

  static readonly TALK_START: string = 'WALKIE_TALK_START'
  static readonly TALK_STOP: string = 'WALKIE_TALK_STOP'
  static readonly TALK_OK: string = 'WALKIE_TALK_OK'
  static readonly TALK_BUSY: string = 'WALKIE_TALK_BUSY'
  static readonly TALK_RELEASED: string = 'WALKIE_TALK_RELEASED'
  static readonly TALKING: string = 'WALKIE_TALKING'

  // ============================================================
  // 频道
  // ============================================================

  static readonly CHANNEL_LIST: string = 'WALKIE_CHANNEL_LIST'
  static readonly CHANNEL_JOINED: string = 'WALKIE_CHANNEL_JOINED'
  static readonly CHANNEL_LEFT: string = 'WALKIE_CHANNEL_LEFT'
  static readonly CHANNEL_CREATED: string = 'WALKIE_CHANNEL_CREATED'
  static readonly CHANNEL_DELETED: string = 'WALKIE_CHANNEL_DELETED'
  static readonly CHANNEL_ERROR: string = 'WALKIE_CHANNEL_ERROR'
  static readonly CHANNEL_INFO: string = 'WALKIE_CHANNEL_INFO'
  static readonly CHANNEL_MEMBERS: string = 'WALKIE_CHANNEL_MEMBERS'

  // ============================================================
  // 网络
  // ============================================================

  static readonly NET_PING: string = 'WALKIE_NET_PING'
  static readonly NET_PONG: string = 'WALKIE_NET_PONG'

  static buildHello(deviceId: string): string {
    return `${WalkieProtocol.HELLO}:${deviceId}`
  }

  static buildLogin(
    deviceId: string,
    nickname: string
  ): string {
    return `${WalkieProtocol.LOGIN}:${deviceId}:${nickname}`
  }

  static buildKeepAlive(): string {
    return WalkieProtocol.KEEPALIVE
  }

  static buildGoodbye(): string {
    return WalkieProtocol.GOODBYE
  }

  static buildTalkStart(): string {
    return WalkieProtocol.TALK_START
  }

  static buildTalkStop(): string {
    return WalkieProtocol.TALK_STOP
  }

  static buildSetNickname(
    nickname: string
  ): string {
    return `${WalkieProtocol.SET_NICKNAME}:${nickname}`
  }

  static buildChannelList(): string {
    return WalkieProtocol.CHANNEL_LIST
  }

  static buildNetPing(
    sequence: number
  ): string {
    return `${WalkieProtocol.NET_PING}:${sequence}`
  }

  static buildJoinChannel(
    channel: string,
    password: string = ''
  ): string {
    return `${WalkieProtocol.CHANNEL_JOINED}:${channel}:${password}`
  }

  static buildCreateChannel(
    channel: string,
    password: string,
    isPrivate: boolean
  ): string {
    const type: string =
      isPrivate ? 'PRIVATE' : 'PUBLIC'

    return `${WalkieProtocol.CHANNEL_CREATED}:${channel}:${password}:${type}`
  }

  static buildDeleteChannel(
    channel: string
  ): string {
    return `${WalkieProtocol.CHANNEL_DELETED}:${channel}`
  }

  static startsWith(
    message: string,
    prefix: string
  ): boolean {
    return (
      message === prefix ||
        message.startsWith(prefix + ':')
    )
  }

  static split(
    message: string
  ): string[] {
    return message.split(':')
  }
}