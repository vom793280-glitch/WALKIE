import { audio } from '@kit.AudioKit'
import { BusinessError } from '@kit.BasicServicesKit'
import { WalkieUdp } from './WalkieUdp'
import { WalkieOpus } from './WalkieOpus'
import { WalkieAudioPacket } from './WalkieAudioPacket'

/*
 * ============================================================
 * WALKIE 音频采集 + Opus + W23A + UDP
 *
 * HarmonyOS 7.0 / API 26
 *
 * 录音：
 *   16000 Hz
 *   Mono
 *   S16LE
 *   RAW
 *
 * 每帧：
 *   320 samples
 *   20 ms
 *   640 bytes PCM
 *
 * 发送：
 *
 *   AudioCapturer
 *        ↓
 *   640B PCM
 *        ↓
 *   Native Opus
 *        ↓
 *   W23A
 *        ↓
 *   当前 WalkieUdp
 *        ↓
 *   VPS : 50000
 *
 * ============================================================
 */

export class WalkieAudio {

  private static readonly SAMPLE_RATE: number =
    16000

  private static readonly FRAME_SAMPLES: number =
    320

  private static readonly FRAME_BYTES: number =
    640

  private udp: WalkieUdp

  private opus: WalkieOpus =
    new WalkieOpus()

  private capturer:
    audio.AudioCapturer | null =
    null

  private running: boolean =
    false

  private starting: boolean =
    false

  private readDataRegistered: boolean =
    false

  private pcmBuffer:
    Uint8Array =
    new Uint8Array(0)

  private streamId: number =
    0

  private sequence: number =
    0

  private pcmFrameCount: number =
    0

  private opusFrameCount: number =
    0

  private packetFrameCount: number =
    0

  private sentPacketCount: number =
    0

  private totalPcmBytes: number =
    0

  private totalOpusBytes: number =
    0

  private totalPacketBytes: number =
    0

  private totalSentBytes: number =
    0

  private sendErrorCount: number =
    0

  constructor(
    udp: WalkieUdp
  ) {

    this.udp =
      udp
  }

  // ============================================================
  // 初始化 AudioCapturer
  // ============================================================

  public async init():
    Promise<boolean> {

    if (
      this.capturer !== null
    ) {

      return true
    }

    /*
     * 必须确认 UDP 已经连接。
     */

    if (
      !this.udp.getBound()
    ) {

      console.error(
        'WALKIE AUDIO: UDP 未连接'
      )

      return false
    }

    try {

      /*
       * ========================================================
       * 对讲/VoIP 使用 VOICE_COMMUNICATION
       *
       * 华为音频规格中：
       * VOIP 通话场景推荐：
       *
       * SOURCE_TYPE_VOICE_COMMUNICATION
       * ========================================================
       */

      const options:
        audio.AudioCapturerOptions = {

        streamInfo: {

          samplingRate:
            audio.AudioSamplingRate.SAMPLE_RATE_48000,

          channels:
            audio.AudioChannel.CHANNEL_1,

          sampleFormat:
            audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,

          encodingType:
            audio.AudioEncodingType.ENCODING_TYPE_RAW
        },

capturerInfo: {

  source:
    audio.SourceType.SOURCE_TYPE_MIC,

  capturerFlags:
    0
}

      }

      console.info(
        'WALKIE AUDIO: 正在创建 AudioCapturer'
      )

      const capturer:
        audio.AudioCapturer =
        await audio.createAudioCapturer(
          options
        )

      this.capturer =
        capturer

      console.info(
        'WALKIE AUDIO: ★AudioCapturer 创建成功★'
      )

      return true

    } catch (
      error
    ) {

      /*
       * ========================================================
       * 不再隐藏真实错误。
       *
       * 这里把 code + message 全部打印出来。
       * ========================================================
       */

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE AUDIO: AudioCapturer 创建失败'
      )

      console.error(
        'WALKIE AUDIO: ' +
        `错误码=${businessError.code}`
      )

      console.error(
        'WALKIE AUDIO: ' +
        `错误信息=${businessError.message}`
      )

      console.error(
        'WALKIE AUDIO: ' +
        `错误详情=${JSON.stringify(error)}`
      )

      this.capturer =
        null

      return false
    }
  }

  // ============================================================
  // 开始录音
  // ============================================================

  public async start():
    Promise<boolean> {

    if (
      this.running
    ) {

      return true
    }

    if (
      this.starting
    ) {

      return false
    }

    if (
      !this.udp.getBound()
    ) {

      console.error(
        'WALKIE AUDIO: UDP 尚未连接'
      )

      return false
    }

    this.starting =
      true

    try {

      /*
       * 创建 AudioCapturer。
       */

      const initialized:
        boolean =
        await this.init()

      if (
        !initialized
      ) {

        return false
      }

      const current:
        audio.AudioCapturer | null =
        this.capturer

      if (
        current === null
      ) {

        console.error(
          'WALKIE AUDIO: AudioCapturer 不存在'
        )

        return false
      }

      /*
       * 创建 Opus Encoder。
       */

      const encoderReady:
        boolean =
        this.opus.createEncoder()

      if (
        !encoderReady
      ) {

        console.error(
          'WALKIE AUDIO: Opus Encoder 创建失败'
        )

        return false
      }

      /*
       * 新一轮讲话。
       */

      this.streamId =
        this.createStreamId()

      this.sequence =
        0

      this.pcmBuffer =
        new Uint8Array(0)

      this.pcmFrameCount =
        0

      this.opusFrameCount =
        0

      this.packetFrameCount =
        0

      this.sentPacketCount =
        0

      this.totalPcmBytes =
        0

      this.totalOpusBytes =
        0

      this.totalPacketBytes =
        0

      this.totalSentBytes =
        0

      this.sendErrorCount =
        0

      /*
       * 注册 PCM 回调。
       */

      if (
        !this.readDataRegistered
      ) {

        current.on(
          'readData',
          (
            data: ArrayBuffer
          ): void => {

            if (
              !this.running
            ) {

              return
            }

            this.onAudioData(
              data
            )
          }
        )

        this.readDataRegistered =
          true
      }

      /*
       * 先标记运行，再 start。
       */

      this.running =
        true

      await current.start()

      console.info(
        'WALKIE AUDIO: ★开始讲话★ ' +
        `stream=${this.streamId}`
      )

      return true

    } catch (
      error
    ) {

      this.running =
        false

      this.opus.destroyEncoder()

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE AUDIO: 启动失败'
      )

      console.error(
        'WALKIE AUDIO: ' +
        `启动错误码=${businessError.code}`
      )

      console.error(
        'WALKIE AUDIO: ' +
        `启动错误信息=${businessError.message}`
      )

      return false

    } finally {

      this.starting =
        false
    }
  }

  // ============================================================
  // 收到 PCM
  // ============================================================

  private onAudioData(
    data: ArrayBuffer
  ):
    void {

    if (
      data.byteLength <= 0
    ) {

      return
    }

    this.totalPcmBytes +=
      data.byteLength

    this.appendPcm(
      data
    )
  }

  // ============================================================
  // 累积 PCM
  // ============================================================

  private appendPcm(
    data: ArrayBuffer
  ):
    void {

    const incoming:
      Uint8Array =
      new Uint8Array(
        data
      )

    const oldLength:
      number =
      this.pcmBuffer.length

    const merged:
      Uint8Array =
      new Uint8Array(
        oldLength +
        incoming.length
      )

    if (
      oldLength > 0
    ) {

      merged.set(
        this.pcmBuffer,
        0
      )
    }

    merged.set(
      incoming,
      oldLength
    )

    this.pcmBuffer =
      merged

    /*
     * 640B = 20ms
     */

    while (
      this.pcmBuffer.length >=
      WalkieAudio.FRAME_BYTES
    ) {

      const frame:
        Uint8Array =
        this.pcmBuffer.slice(
          0,
          WalkieAudio.FRAME_BYTES
        )

      this.pcmBuffer =
        this.pcmBuffer.slice(
          WalkieAudio.FRAME_BYTES
        )

      this.onPcmFrame(
        frame
      )
    }

    /*
     * 缓冲保护。
     */

    const maxBufferBytes:
      number =
      WalkieAudio.FRAME_BYTES * 10

    if (
      this.pcmBuffer.length >
      maxBufferBytes
    ) {

      this.pcmBuffer =
        this.pcmBuffer.slice(
          this.pcmBuffer.length -
          WalkieAudio.FRAME_BYTES
        )

      console.warn(
        'WALKIE AUDIO: PCM缓冲过大，已丢弃旧数据'
      )
    }
  }

  // ============================================================
  // PCM → Opus → W23A → UDP
  // ============================================================

  private onPcmFrame(
    frame: Uint8Array
  ):
    void {

    if (
      frame.length !==
      WalkieAudio.FRAME_BYTES
    ) {

      return
    }

    this.pcmFrameCount +=
      1

    /*
     * TypedArray → 独立 ArrayBuffer。
     */

    const pcmBuffer:
      ArrayBuffer =
      new ArrayBuffer(
        WalkieAudio.FRAME_BYTES
      )

    const pcmView:
      Uint8Array =
      new Uint8Array(
        pcmBuffer
      )

    pcmView.set(
      frame
    )

    /*
     * ========================================================
     * PCM → Opus
     * ========================================================
     */

    const opusData:
      ArrayBuffer | null =
      this.opus.encode(
        pcmBuffer
      )

    if (
      opusData === null
    ) {

      console.error(
        'WALKIE AUDIO: Opus 编码失败'
      )

      return
    }

    this.opusFrameCount +=
      1

    this.totalOpusBytes +=
      opusData.byteLength

    /*
     * ========================================================
     * Opus → W23A
     * ========================================================
     */

    const packet:
      ArrayBuffer =
      WalkieAudioPacket.build(
        this.streamId,
        this.sequence,
        opusData
      )

    this.packetFrameCount +=
      1

    this.totalPacketBytes +=
      packet.byteLength

    /*
     * Sequence +1。
     */

    this.sequence =
      this.nextSequence(
        this.sequence
      )

    /*
     * ========================================================
     * 发送
     * ========================================================
     */

    void this.sendPacket(
      packet
    )

    /*
     * 每 25 帧打印一次。
     */

    if (
      this.packetFrameCount % 25 ===
      0
    ) {

      console.info(
        'WALKIE AUDIO: ' +
        `PCM=${this.pcmFrameCount} ` +
        `Opus=${this.opusFrameCount} ` +
        `W23A=${this.packetFrameCount} ` +
        `已发送=${this.sentPacketCount} ` +
        `Opus大小=${opusData.byteLength}B ` +
        `Packet大小=${packet.byteLength}B ` +
        `Sequence=${this.sequence} ` +
        `发送失败=${this.sendErrorCount}`
      )
    }
  }

  // ============================================================
  // UDP 发送
  // ============================================================

  private async sendPacket(
    packet: ArrayBuffer
  ):
    Promise<void> {

    if (
      !this.running
    ) {

      return
    }

    if (
      !this.udp.getBound()
    ) {

      this.sendErrorCount +=
        1

      return
    }

    try {

      await this.udp.send(
        packet
      )

      this.sentPacketCount +=
        1

      this.totalSentBytes +=
        packet.byteLength

    } catch (
      error
    ) {

      this.sendErrorCount +=
        1

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE AUDIO: UDP音频发送失败'
      )

      console.error(
        'WALKIE AUDIO: ' +
        `发送错误码=${businessError.code}`
      )

      console.error(
        'WALKIE AUDIO: ' +
        `发送错误信息=${businessError.message}`
      )
    }
  }

  // ============================================================
  // Sequence
  // ============================================================

  private nextSequence(
    value: number
  ):
    number {

    if (
      value >=
      0xFFFF_FFFE
    ) {

      return 0
    }

    return value + 1
  }

  // ============================================================
  // StreamID
  // ============================================================

  private createStreamId():
    number {

    const time:
      number =
      Date.now()

    const random:
      number =
      Math.floor(
        Math.random() *
        0xFFFF
      )

    return (
      (
        time +
        random
      ) >>> 0
    )
  }

  // ============================================================
  // 停止
  // ============================================================

  public async stop():
    Promise<void> {

    if (
      !this.running &&
      !this.starting
    ) {

      return
    }

    this.running =
      false

    const current:
      audio.AudioCapturer | null =
      this.capturer

    if (
      current !== null
    ) {

      try {

        await current.stop()

      } catch (
        error
      ) {

        const businessError:
          BusinessError =
          error as BusinessError

        console.error(
          'WALKIE AUDIO: 停止录音失败，' +
          `code=${businessError.code} ` +
          `message=${businessError.message}`
        )
      }
    }

    this.pcmBuffer =
      new Uint8Array(0)

    this.opus.destroyEncoder()

    console.info(
      'WALKIE AUDIO: ★停止讲话★ ' +
      `PCM=${this.pcmFrameCount} ` +
      `Opus=${this.opusFrameCount} ` +
      `W23A=${this.packetFrameCount} ` +
      `发送=${this.sentPacketCount}`
    )
  }

  // ============================================================
  // 释放
  // ============================================================

  public async release():
    Promise<void> {

    this.running =
      false

    this.starting =
      false

    const current:
      audio.AudioCapturer | null =
      this.capturer

    if (
      current !== null
    ) {

      try {

        current.off(
          'readData'
        )

      } catch {
        // 忽略
      }

      try {

        await current.release()

      } catch {
        // 忽略
      }
    }

    this.readDataRegistered =
      false

    this.opus.destroy()

    this.capturer =
      null

    this.pcmBuffer =
      new Uint8Array(0)
  }

  // ============================================================
  // 状态
  // ============================================================

  public isRunning():
    boolean {

    return this.running
  }

  // ============================================================
  // PCM 帧数
  // ============================================================

  public getPcmFrameCount():
    number {

    return this.pcmFrameCount
  }

  // ============================================================
  // Opus 帧数
  // ============================================================

  public getOpusFrameCount():
    number {

    return this.opusFrameCount
  }

  // ============================================================
  // W23A 帧数
  // ============================================================

  public getPacketFrameCount():
    number {

    return this.packetFrameCount
  }

  // ============================================================
  // 已发送
  // ============================================================

  public getSentPacketCount():
    number {

    return this.sentPacketCount
  }

  // ============================================================
  // 发送失败
  // ============================================================

  public getSendErrorCount():
    number {

    return this.sendErrorCount
  }

  // ============================================================
  // PCM 总字节
  // ============================================================

  public getTotalPcmBytes():
    number {

    return this.totalPcmBytes
  }

  // ============================================================
  // Opus 总字节
  // ============================================================

  public getTotalOpusBytes():
    number {

    return this.totalOpusBytes
  }

  // ============================================================
  // W23A 总字节
  // ============================================================

  public getTotalPacketBytes():
    number {

    return this.totalPacketBytes
  }

  // ============================================================
  // 已发送总字节
  // ============================================================

  public getTotalSentBytes():
    number {

    return this.totalSentBytes
  }

  // ============================================================
  // StreamID
  // ============================================================

  public getStreamId():
    number {

    return this.streamId
  }

  // ============================================================
  // Sequence
  // ============================================================

  public getSequence():
    number {

    return this.sequence
  }

  // ============================================================
  // 采样率
  // ============================================================

  public getSampleRate():
    number {

    return WalkieAudio.SAMPLE_RATE
  }

  // ============================================================
  // 每帧采样数
  // ============================================================

  public getFrameSamples():
    number {

    return WalkieAudio.FRAME_SAMPLES
  }

  // ============================================================
  // 每帧 PCM 大小
  // ============================================================

  public getFrameBytes():
    number {

    return WalkieAudio.FRAME_BYTES
  }

  // ============================================================
  // 每帧时长
  // ============================================================

  public getFrameDurationMs():
    number {

    return 20
  }
}