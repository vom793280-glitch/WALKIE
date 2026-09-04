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
 * V23.4
 *
 * 统一音频参数：
 *
 *   16000 Hz
 *   Mono
 *   S16LE
 *   RAW
 *
 * 每帧：
 *
 *   320 samples
 *   20 ms
 *   640 bytes PCM
 *
 * 发送：
 *
 *   AudioCapturer
 *        ↓
 *   16kHz PCM
 *        ↓
 *   640B / 20ms
 *        ↓
 *   Native Opus
 *        ↓
 *   W23A
 *        ↓
 *   单发送队列
 *        ↓
 *   WalkieUdp
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

  /*
   * 实时音频发送队列。
   *
   * 不允许弱网时无限堆积。
   *
   * 4 个包约等于：
   *
   * 4 × 20ms = 80ms
   *
   * 超过以后丢弃最旧数据，
   * 保留最新音频。
   */

  private static readonly SEND_QUEUE_LIMIT: number =
    4

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

  /*
   * PCM 累积缓冲。
   */

  private pcmBuffer:
    Uint8Array =
    new Uint8Array(0)

  /*
   * UDP 音频发送队列。
   */

  private sendQueue:
    Array<ArrayBuffer> =
    []

  /*
   * 当前是否正在执行 UDP 发送。
   *
   * 永远只允许一个 send() 同时进行。
   */

  private sending:
    boolean =
    false

  /*
   * 用于区分不同一轮讲话。
   *
   * stop() 后旧发送循环即使异步返回，
   * 也不能继续处理上一轮数据。
   */

  private talkGeneration:
    number =
    0

  /*
   * 统计。
   */

  private streamId:
    number =
    0

  private sequence:
    number =
    0

  private pcmFrameCount:
    number =
    0

  private opusFrameCount:
    number =
    0

  private packetFrameCount:
    number =
    0

  private sentPacketCount:
    number =
    0

  private droppedPacketCount:
    number =
    0

  private totalPcmBytes:
    number =
    0

  private totalOpusBytes:
    number =
    0

  private totalPacketBytes:
    number =
    0

  private totalSentBytes:
    number =
    0

  private sendErrorCount:
    number =
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
       * 统一使用：
       *
       * 16000 Hz
       * Mono
       * S16LE
       * RAW
       *
       * 与 Native Opus 完全一致。
       * ========================================================
       */

      const options:
        audio.AudioCapturerOptions = {

        streamInfo: {

          samplingRate:
          audio.AudioSamplingRate.SAMPLE_RATE_16000,

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

      console.info(
        'WALKIE AUDIO: ' +
          '采样率=16000Hz ' +
          'Mono S16LE RAW'
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
       * ========================================================
       * 新一轮讲话。
       * ========================================================
       */

      this.talkGeneration +=
      1

      this.streamId =
        this.createStreamId()

      this.sequence =
        0

      this.pcmBuffer =
        new Uint8Array(0)

      /*
       * 清除上一轮可能残留的发送包。
       */

      this.clearSendQueue()

      this.pcmFrameCount =
        0

      this.opusFrameCount =
        0

      this.packetFrameCount =
        0

      this.sentPacketCount =
        0

      this.droppedPacketCount =
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
       *
       * 这里只做数据进入队列，
       * 不进行 UDP 等耗时操作。
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
          `stream=${this.streamId} ` +
          `generation=${this.talkGeneration}`
      )

      return true

    } catch (
    error
    ) {

      this.running =
        false

      this.clearSendQueue()

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

      console.error(
        'WALKIE AUDIO: ' +
          `启动错误详情=${JSON.stringify(error)}`
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
     * ========================================================
     * 640B = 320 samples = 20ms @ 16kHz
     * ========================================================
     */

    while (
      this.pcmBuffer.length >=
        WalkieAudio.FRAME_BYTES
    ) {

      /*
       * 如果已经停止讲话，
       * 不再继续产生 PCM 帧。
       */

      if (
        !this.running
      ) {

        this.pcmBuffer =
          new Uint8Array(0)

        return
      }

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
     * ========================================================
     * 缓冲保护
     *
     * 最多保留 10 帧。
     * ========================================================
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
        'WALKIE AUDIO: ' +
          'PCM缓冲过大，已丢弃旧数据'
      )
    }
  }

  // ============================================================
  // PCM → Opus → W23A
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

    if (
      !this.running
    ) {

      return
    }

    this.pcmFrameCount +=
    1

    /*
     * ========================================================
     * PCM → 独立 ArrayBuffer
     * ========================================================
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
     * Sequence +1
     */

    this.sequence =
      this.nextSequence(
        this.sequence
      )

    /*
     * ========================================================
     * 加入发送队列
     * ========================================================
     */

    this.enqueuePacket(
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
          `队列=${this.sendQueue.length} ` +
          `丢弃=${this.droppedPacketCount} ` +
          `Opus大小=${opusData.byteLength}B ` +
          `Packet大小=${packet.byteLength}B ` +
          `Sequence=${this.sequence} ` +
          `发送失败=${this.sendErrorCount}`
      )
    }
  }

  // ============================================================
  // 加入 UDP 音频发送队列
  // ============================================================

  private enqueuePacket(
    packet: ArrayBuffer
  ):
    void {

    if (
      !this.running
    ) {

      return
    }

    /*
     * 队列满：
     *
     * 丢弃最旧包，
     * 保留最新包。
     *
     * 对讲机实时语音宁可少一小段，
     * 也不能让延迟越来越大。
     */

    while (
      this.sendQueue.length >=
        WalkieAudio.SEND_QUEUE_LIMIT
    ) {

      this.sendQueue.shift()

      this.droppedPacketCount +=
      1
    }

    this.sendQueue.push(
      packet
    )

    if (
      this.droppedPacketCount > 0 &&
        this.droppedPacketCount % 10 === 0
    ) {

      console.warn(
        'WALKIE AUDIO: ' +
          `弱网，累计丢弃旧音频包=${this.droppedPacketCount}`
      )
    }

    /*
     * 只启动一个发送器。
     */

    if (
      !this.sending
    ) {

      const generation:
        number =
        this.talkGeneration

      void this.drainSendQueue(
        generation
      )
    }
  }

  // ============================================================
  // 单发送器
  // ============================================================

  private async drainSendQueue(
    generation: number
  ):
    Promise<void> {

    if (
      this.sending
    ) {

      return
    }

    this.sending =
      true

    try {

      while (
        this.running &&
          generation === this.talkGeneration
      ) {

        if (
          this.sendQueue.length === 0
        ) {

          break
        }

        if (
          !this.udp.getBound()
        ) {

          this.sendErrorCount +=
          1

          this.clearSendQueue()

          break
        }

        const packet:
        ArrayBuffer | undefined =
          this.sendQueue.shift()

        if (
          packet === undefined
        ) {

          continue
        }

        try {

          await this.udp.send(
            packet
          )

          /*
           * 如果等待 UDP 期间讲话已经停止，
           * 当前这个已经发出去的包不重复统计为错误。
           */

          if (
            generation === this.talkGeneration
          ) {

            this.sentPacketCount +=
            1

            this.totalSentBytes +=
            packet.byteLength
          }

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

          /*
           * 当前包发送失败以后，
           * 不继续无限堆积旧音频。
           *
           * 保留少量最新包即可。
           */

          if (
            this.sendQueue.length >
              1
          ) {

            while (
              this.sendQueue.length >
                1
            ) {

              this.sendQueue.shift()

              this.droppedPacketCount +=
              1
            }
          }
        }
      }

    } finally {

      this.sending =
        false

      /*
       * 理论上这里通常为空。
       *
       * 防止在发送器退出与新包加入之间
       * 出现竞态造成队列没人处理。
       */

      if (
        this.running &&
          generation === this.talkGeneration &&
          this.sendQueue.length > 0
      ) {

        void this.drainSendQueue(
          generation
        )
      }
    }
  }

  // ============================================================
  // 清空发送队列
  // ============================================================

  private clearSendQueue():
    void {

    this.sendQueue =
      []
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

    /*
     * 先切换 generation。
     *
     * 让旧发送循环失效。
     */

    this.talkGeneration +=
    1

    /*
     * 立即停止产生新的音频包。
     */

    this.running =
      false

    /*
     * 清除还没有发送的旧包。
     */

    this.clearSendQueue()

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

    /*
     * 清空未完成 PCM。
     */

    this.pcmBuffer =
      new Uint8Array(0)

    /*
     * 销毁本轮 Encoder。
     */

    this.opus.destroyEncoder()

    console.info(
      'WALKIE AUDIO: ★停止讲话★ ' +
        `PCM=${this.pcmFrameCount} ` +
        `Opus=${this.opusFrameCount} ` +
        `W23A=${this.packetFrameCount} ` +
        `发送=${this.sentPacketCount} ` +
        `丢弃=${this.droppedPacketCount} ` +
        `发送失败=${this.sendErrorCount}`
    )
  }

  // ============================================================
  // 释放
  // ============================================================

  public async release():
    Promise<void> {

    /*
     * 使全部旧发送循环立即失效。
     */

    this.talkGeneration +=
    1

    this.running =
      false

    this.starting =
      false

    /*
     * 清空发送队列。
     */

    this.clearSendQueue()

    /*
     * 清空 PCM。
     */

    this.pcmBuffer =
      new Uint8Array(0)

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

    this.clearSendQueue()
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
  // 丢弃
  // ============================================================

  public getDroppedPacketCount():
    number {

    return this.droppedPacketCount
  }

  // ============================================================
  // 发送失败
  // ============================================================

  public getSendErrorCount():
    number {

    return this.sendErrorCount
  }

  // ============================================================
  // 队列长度
  // ============================================================

  public getSendQueueLength():
    number {

    return this.sendQueue.length
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