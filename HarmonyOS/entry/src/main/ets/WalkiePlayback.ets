import {
  audio
} from '@kit.AudioKit'

import {
  BusinessError
} from '@kit.BasicServicesKit'

import {
  WalkieOpus
} from './WalkieOpus'

import {
  WalkieAudioPacket
} from './WalkieAudioPacket'


/*
 * ============================================================
 * WALKIE HarmonyOS
 *
 * V24.8.6
 *
 * Android → HarmonyOS 播放
 *
 * 当前播放链路：
 *
 *   Android Opus
 *        ↓
 *   W23A + StreamID + Sequence
 *        ↓
 *   VPS V21 透明转发
 *        ↓
 *   HarmonyOS W23A
 *        ↓
 *   Opus Decoder
 *        ↓
 *   16kHz / Mono / S16LE PCM
 *        ↓
 *   AudioSessionManager
 *        ↓
 *   SPEAKER
 *        ↓
 *   AudioRenderer
 *
 * V24.6：
 *
 *   1. 正常顺序包立即播放
 *   2. Sequence 跳号进入短暂重排等待
 *   3. 等待窗口 15ms
 *   4. missing=1 时使用 Opus PLC 补 1 帧
 *   5. missing>=2 暂不 PLC
 *   6. PLC PCM 进入现有播放队列
 *   7. 不修改音量
 *   8. 不修改 AudioSession
 *   9. 不修改 SPEAKER
 *   10. 不修改 PCM 数据
 *   11. 修复 Renderer 重复 start
 *
 * ============================================================
 */

export class WalkiePlayback {

  // ============================================================
  // AudioSession
  // ============================================================

  private audioManager:
  audio.AudioManager | null =
    null

  private audioSessionManager:
  audio.AudioSessionManager | null =
    null

  private audioSessionActive:
    boolean =
    false

  // ============================================================
  // AudioRenderer
  // ============================================================

  private renderer:
  audio.AudioRenderer | null =
    null

  private rendererReady:
    boolean =
    false

  private rendererStarting:
    boolean =
    false

  // ============================================================
  // Opus Decoder
  // ============================================================

  private opus:
    WalkieOpus =
    new WalkieOpus()

  // ============================================================
  // PCM 播放队列
  // ============================================================

  private pcmQueue:
    Array<Uint8Array> =
    []

  /*
   * Android V24.8.4 自适应播放缓冲基线：
   *
   * 良好网络：12 帧 ≈ 240ms
   * 中等弱网：16 帧 ≈ 320ms
   * 严重弱网：20 帧 ≈ 400ms
   *
   * 这里保留 12 作为基础值，
   * 实际上限由网络质量动态决定。
   */
  private readonly pcmQueueLimit:
    number =
    12

  /*
   * Android 网络质量 → 播放策略。
   */
  private networkLatencyMs:
    number =
    -1

  private networkLossPercent:
    number =
    0

  private networkJitterMs:
    number =
    -1

  private currentPcm:
  Uint8Array | null =
    null

  private currentPcmOffset:
    number =
    0

  // ============================================================
  // UDP 音频包处理队列
  // ============================================================

  private packetQueue:
    ArrayBuffer[] =
    []

  private packetProcessing:
    boolean =
    false

  // ============================================================
  // 播放状态
  // ============================================================

  private playing:
    boolean =
    false

  private started:
    boolean =
    false

  // ============================================================
  // W23A Stream
  // ============================================================

  private currentStreamId:
    number =
    -1

  private expectedSequence:
    number =
    -1

  private lastSequence:
    number =
    -1

  // ============================================================
  // W23A 重排缓冲
  // ============================================================

  private reorderBuffer:
    Map<number, ArrayBuffer> =
    new Map<number, ArrayBuffer>()

  /*
   * 轻微乱序等待时间。
   */

  private static readonly REORDER_WAIT_MS:
    number =
    15

  /*
   * V24.8.6
   *
   * 网络抖动较大时，适当延长重排等待。
   * 正常网络仍保持 15ms。
   */
  private getReorderWaitMs():
    number {

    if (
      this.networkJitterMs >= 50 ||
        this.networkLossPercent >= 5
    ) {

      return 40
    }

    if (
      this.networkJitterMs >= 30
    ) {

      return 30
    }

    return WalkiePlayback.REORDER_WAIT_MS
  }

  /*
   * 防止异常 Sequence 大跳。
   */

  private static readonly MAX_SEQUENCE_JUMP:
    number =
    1000

  /*
   * 当前正在等待哪个 Sequence。
   */

  private reorderWaitingSequence:
    number =
    -1

  private reorderTimer:
  number | null =
    null

  // ============================================================
  // 播放参数
  // ============================================================

  private static readonly PLAYBACK_VOLUME:
    number =
    1.0

  // ============================================================
  // AudioSession
  // ============================================================

  private async ensureAudioSession():
    Promise<boolean> {

    if (
      this.audioSessionManager !== null &&
        this.audioSessionActive
    ) {

      return true
    }

    try {

      if (
        this.audioManager ===
          null
      ) {

        this.audioManager =
          audio.getAudioManager()
      }

      if (
        this.audioSessionManager ===
          null
      ) {

        this.audioSessionManager =
          this.audioManager
            .getSessionManager()
      }

      const sessionManager:
        audio.AudioSessionManager =
        this.audioSessionManager

      /*
       * VOICE_COMMUNICATION
       */

      sessionManager.setAudioSessionScene(
        audio.AudioSessionScene
          .AUDIO_SESSION_SCENE_VOICE_COMMUNICATION
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          'AudioSessionScene=VOICE_COMMUNICATION'
      )

      /*
       * 激活 AudioSession
       */

      if (
        !this.audioSessionActive
      ) {

        const strategy:
          audio.AudioSessionStrategy = {

          concurrencyMode:
          audio.AudioConcurrencyMode
            .CONCURRENCY_MIX_WITH_OTHERS
        }

        await sessionManager.activateAudioSession(
          strategy
        )

        this.audioSessionActive =
          true

        console.info(
          'WALKIE PLAYBACK: ' +
            '★AudioSession 已激活★'
        )
      }

      /*
       * 应用级默认输出：
       * SPEAKER
       */

      await sessionManager.setDefaultOutputDevice(
        audio.DeviceType.SPEAKER
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          '★AudioSessionManager → SPEAKER★'
      )

      const defaultDevice:
        audio.DeviceType =
        sessionManager.getDefaultOutputDevice()

      if (
        defaultDevice ===
          audio.DeviceType.SPEAKER
      ) {

        console.info(
          'WALKIE PLAYBACK: ' +
            '★应用级默认输出确认 = SPEAKER★'
        )

      } else if (
        defaultDevice ===
          audio.DeviceType.EARPIECE
      ) {

        console.warn(
          'WALKIE PLAYBACK: ' +
            '应用级默认输出仍然是 EARPIECE'
        )

      } else {

        console.info(
          'WALKIE PLAYBACK: ' +
            `应用级默认输出设备类型=${defaultDevice}`
        )
      }

      return true

    } catch (
    error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE PLAYBACK: ' +
          'AudioSession 初始化失败 ' +
          `code=${businessError.code} ` +
          `message=${businessError.message}`
      )

      return false
    }
  }

  // ============================================================
  // AudioRenderer 创建
  // ============================================================

  private async ensureRenderer():
    Promise<boolean> {

    if (
      this.renderer !== null &&
        this.rendererReady
    ) {

      return true
    }

    const sessionReady:
      boolean =
      await this.ensureAudioSession()

    if (
      !sessionReady
    ) {

      return false
    }

    /*
     * 防止多个异步流程同时创建 Renderer。
     */

    if (
      this.rendererStarting
    ) {

      for (
        let index:
          number =
          0;
        index < 30;
        index++
      ) {

        if (
          this.renderer !== null &&
            this.rendererReady
        ) {

          return true
        }

        await this.sleep(
          10
        )
      }

      return (
        this.renderer !== null &&
          this.rendererReady
      )
    }

    this.rendererStarting =
      true

    try {

      const streamInfo:
        audio.AudioStreamInfo = {

        samplingRate:
        audio.AudioSamplingRate
          .SAMPLE_RATE_16000,

        channels:
        audio.AudioChannel.CHANNEL_1,

        sampleFormat:
        audio.AudioSampleFormat
          .SAMPLE_FORMAT_S16LE,

        encodingType:
        audio.AudioEncodingType
          .ENCODING_TYPE_RAW
      }

      const rendererInfo:
        audio.AudioRendererInfo = {

        usage:
        audio.StreamUsage
          .STREAM_USAGE_VOICE_COMMUNICATION,

        rendererFlags:
        0,

        volumeMode:
        audio.AudioVolumeMode
          .SYSTEM_GLOBAL
      }

      const options:
        audio.AudioRendererOptions = {

        streamInfo:
        streamInfo,

        rendererInfo:
        rendererInfo
      }

      /*
       * 创建 Renderer
       */

      const renderer:
        audio.AudioRenderer =
        await audio.createAudioRenderer(
          options
        )

      this.renderer =
        renderer

      /*
       * writeData
       */

      renderer.on(
        'writeData',
        (
          buffer:
            ArrayBuffer
        ):
          void => {

          this.onWriteData(
            buffer
          )
        }
      )

      /*
       * Renderer → SPEAKER
       */

      try {

        await renderer.setDefaultOutputDevice(
          audio.DeviceType.SPEAKER
        )

        console.info(
          'WALKIE PLAYBACK: ' +
            '★AudioRenderer → SPEAKER★'
        )

      } catch (
      error
      ) {

        const businessError:
          BusinessError =
          error as BusinessError

        console.error(
          'WALKIE PLAYBACK: ' +
            'Renderer 设置 SPEAKER 失败 ' +
            `code=${businessError.code} ` +
            `message=${businessError.message}`
        )
      }

      /*
       * volume=1.0
       */

      try {

        renderer.setVolume(
          WalkiePlayback.PLAYBACK_VOLUME
        )

        console.info(
          'WALKIE PLAYBACK: ' +
            '★Renderer volume=1.0★'
        )

      } catch (
      error
      ) {

        const businessError:
          BusinessError =
          error as BusinessError

        console.error(
          'WALKIE PLAYBACK: ' +
            'Renderer 设置 volume 失败 ' +
            `code=${businessError.code} ` +
            `message=${businessError.message}`
        )
      }

      this.rendererReady =
        true

      console.info(
        'WALKIE PLAYBACK: ' +
          'AudioRenderer 创建成功'
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          'sampleRate=16000 ' +
          'channels=1 ' +
          'format=S16LE'
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          'usage=VOICE_COMMUNICATION'
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          'output=SPEAKER volume=1.0'
      )

      return true

    } catch (
    error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE PLAYBACK: ' +
          'AudioRenderer 创建失败 ' +
          `code=${businessError.code} ` +
          `message=${businessError.message}`
      )

      this.renderer =
        null

      this.rendererReady =
        false

      return false

    } finally {

      this.rendererStarting =
        false
    }
  }

  // ============================================================
  // PCM 输出
  // ============================================================

  private onWriteData(
    buffer:
      ArrayBuffer
  ):
    void {

    const output:
      Uint8Array =
      new Uint8Array(
        buffer
      )

    output.fill(
      0
    )

    let outputOffset:
      number =
      0

    while (
      outputOffset <
        output.length
    ) {

      if (
        this.currentPcm === null ||
          this.currentPcmOffset >=
            this.currentPcm.length
      ) {

        if (
          this.pcmQueue.length ===
            0
        ) {

          break
        }

        this.currentPcm =
          this.pcmQueue.shift()
            ?? null

        this.currentPcmOffset =
          0

        if (
          this.currentPcm ===
            null
        ) {

          break
        }
      }

      const current:
        Uint8Array =
        this.currentPcm

      const remainingInput:
        number =
        current.length -
          this.currentPcmOffset

      const remainingOutput:
        number =
        output.length -
          outputOffset

      const copyLength:
        number =
        Math.min(
          remainingInput,
          remainingOutput
        )

      output.set(
        current.subarray(
          this.currentPcmOffset,
          this.currentPcmOffset +
            copyLength
        ),
        outputOffset
      )

      outputOffset +=
      copyLength

      this.currentPcmOffset +=
      copyLength
    }
  }

  // ============================================================
  // 收到 UDP 音频包
  // ============================================================

  public async playPacket(
    data:
      ArrayBuffer
  ):
    Promise<void> {

    if (
      data.byteLength <=
        0
    ) {

      return
    }

    this.packetQueue.push(
      data
    )

    /*
     * 防止弱网时处理延迟无限增长。
     */

    while (
      this.packetQueue.length >
        30
    ) {

      this.packetQueue.shift()
    }

    if (
      !this.packetProcessing
    ) {

      await this.processPacketQueue()
    }
  }

  // ============================================================
  // 串行处理 UDP 包
  // ============================================================

  private async processPacketQueue():
    Promise<void> {

    if (
      this.packetProcessing
    ) {

      return
    }

    this.packetProcessing =
      true

    try {

      while (
        this.packetQueue.length >
          0
      ) {

        const data:
        ArrayBuffer | undefined =
          this.packetQueue.shift()

        if (
          data ===
            undefined
        ) {

          continue
        }

        await this.processOnePacket(
          data
        )
      }

    } catch (
    error
    ) {

      console.error(
        'WALKIE PLAYBACK: ' +
          '音频队列异常=' +
          JSON.stringify(
            error
          )
      )

    } finally {

      this.packetProcessing =
        false

      /*
       * 处理期间又收到数据，
       * 继续处理。
       */

      if (
        this.packetQueue.length >
          0
      ) {

        void this.processPacketQueue()
      }
    }
  }

  // ============================================================
  // Android V23.1 → HarmonyOS V24.6
  // 自适应播放策略
  // ============================================================

  /**
   * 由 WalkieClient 提供当前网络质量。
   *
   * Android 的网络质量判断不是只看延迟，
   * 而是同时考虑 loss / latency / jitter。
   */
  public setNetworkQuality(
    latency:
      number,
    loss:
      number,
    jitter:
      number
  ):
    void {

    this.networkLatencyMs =
      latency

    this.networkLossPercent =
      Math.max(
        0,
        Math.min(
          100,
          loss
        )
      )

    this.networkJitterMs =
      jitter

    console.info(
      'WALKIE PLAYBACK: ' +
        '自适应网络参数更新 ' +
        `latency=${this.networkLatencyMs}ms ` +
        `loss=${this.networkLossPercent}% ` +
        `jitter=${this.networkJitterMs}ms ` +
        `queueLimit=${this.getDynamicPcmQueueLimit()} ` +
        `startBuffer=${this.getAdaptiveStartBufferPackets()}`
    )
  }

  /**
   * Android V23.1 对应的动态最大播放队列。
   */
  private getDynamicPcmQueueLimit():
    number {

    const loss:
      number =
      this.networkLossPercent

    const latency:
      number =
      this.networkLatencyMs

    const jitter:
      number =
      this.networkJitterMs

    if (
      loss >= 12 ||
        latency >= 250 ||
        jitter >= 70
    ) {

      return 20
    }

    if (
      loss >= 5 ||
        latency >= 150 ||
        jitter >= 35
    ) {

      return 16
    }

    return this.pcmQueueLimit
  }

  /**
   * Android V23.1 对应的恢复/起播缓冲。
   */
  private getAdaptiveStartBufferPackets():
    number {

    const loss:
      number =
      this.networkLossPercent

    const latency:
      number =
      this.networkLatencyMs

    const jitter:
      number =
      this.networkJitterMs

    if (
      loss >= 12 ||
        latency >= 250 ||
        jitter >= 70
    ) {

      return 6
    }

    if (
      loss >= 5 ||
        latency >= 150 ||
        jitter >= 35
    ) {

      return 4
    }

    return 3
  }

  // ============================================================
  // Opus 解码 + PCM 入队
  // ============================================================

  private async decodeAndQueue(
    opusData:
      ArrayBuffer
  ):
    Promise<void> {

    if (
      opusData.byteLength <=
        0
    ) {

      return
    }

    const pcmData:
    ArrayBuffer | null =
      this.opus.decode(
        opusData
      )

    if (
      pcmData ===
        null
    ) {

      console.error(
        'WALKIE PLAYBACK: ' +
          `Opus 解码失败 length=${opusData.byteLength}`
      )

      return
    }

    if (
      pcmData.byteLength <=
        0
    ) {

      return
    }

    const ready:
      boolean =
      await this.ensureRenderer()

    if (
      !ready
    ) {

      return
    }

    const pcmBytes:
      Uint8Array =
      new Uint8Array(
        pcmData
      )

    if (
      pcmBytes.length <=
        0
    ) {

      return
    }

    /*
     * 防止播放延迟越来越高。
     */

    while (
      this.pcmQueue.length >=
        this.getDynamicPcmQueueLimit()
    ) {

      this.pcmQueue.shift()
    }

    this.pcmQueue.push(
      pcmBytes
    )

    /*
     * 首次起播：
     *
     * 动态起播缓冲：3 / 4 / 6 帧
     */

    if (
      !this.started &&
        !this.playing &&
        this.pcmQueue.length >=
          this.getAdaptiveStartBufferPackets()
    ) {

      await this.startRenderer()
    }
  }

  // ============================================================
  // 清空重排状态
  // ============================================================

  private clearReorderState():
    void {

    if (
      this.reorderTimer !==
        null
    ) {

      clearTimeout(
        this.reorderTimer
      )

      this.reorderTimer =
        null
    }

    this.reorderWaitingSequence =
      -1

    this.reorderBuffer.clear()

    this.expectedSequence =
      -1

    this.lastSequence =
      -1
  }

  // ============================================================
  // 新 W23A 音频流
  // ============================================================

  private beginNewStream(
    streamId:
      number,
    sequence:
      number
  ):
    void {

    this.clearReorderState()

    this.currentStreamId =
      streamId

    this.expectedSequence =
      sequence

    this.lastSequence =
      sequence - 1

    /*
     * 新讲话流清空旧 PCM。
     */

    this.pcmQueue =
      []

    this.currentPcm =
      null

    this.currentPcmOffset =
      0

    console.info(
      'WALKIE PLAYBACK: ' +
        `W23A新音频流 ` +
        `stream=${streamId} ` +
        `firstSequence=${sequence}`
    )
  }

  // ============================================================
  // 找到缓冲区最小 Sequence
  // ============================================================

  private findSmallestBufferedSequence():
    number {

    let smallest:
      number =
      -1

    for (
      const sequence of
      this.reorderBuffer.keys()
    ) {

      if (
        smallest <
          0 ||
          sequence <
            smallest
      ) {

        smallest =
          sequence
      }
    }

    return smallest
  }

  // ============================================================
  // 解码一个 W23A 包
  // ============================================================

  private async decodeW23APacket(
    data:
      ArrayBuffer
  ):
    Promise<void> {

    const opusData:
    ArrayBuffer | null =
      WalkieAudioPacket.extractOpus(
        data
      )

    if (
      opusData ===
        null ||
        opusData.byteLength <=
          0
    ) {

      return
    }

    await this.decodeAndQueue(
      opusData
    )
  }

  // ============================================================
  // 刷新连续 Sequence
  // ============================================================

  private async flushReorderBuffer():
    Promise<void> {

    if (
      this.expectedSequence <
        0
    ) {

      return
    }

    while (
      this.reorderBuffer.has(
        this.expectedSequence
      )
    ) {

      const sequence:
        number =
        this.expectedSequence

      const data:
      ArrayBuffer | undefined =
        this.reorderBuffer.get(
          sequence
        )

      this.reorderBuffer.delete(
        sequence
      )

      if (
        data ===
          undefined
      ) {

        continue
      }

      await this.decodeW23APacket(
        data
      )

      this.lastSequence =
        sequence

      this.expectedSequence =
        sequence + 1
    }

    /*
     * 如果缓存已经清空，
     * 就取消等待计时器。
     */

    if (
      this.reorderBuffer.size ===
        0
    ) {

      this.cancelReorderTimerIfResolved()
    }
  }

  // ============================================================
  // 取消已经解决的重排等待
  // ============================================================

  private cancelReorderTimerIfResolved():
    void {

    if (
      this.reorderWaitingSequence <
        0
    ) {

      return
    }

    if (
      this.expectedSequence !==
        this.reorderWaitingSequence
    ) {

      if (
        this.reorderTimer !==
          null
      ) {

        clearTimeout(
          this.reorderTimer
        )

        this.reorderTimer =
          null
      }

      this.reorderWaitingSequence =
        -1
    }
  }

  // ============================================================
  // 开始 15ms 重排等待
  // ============================================================

  private startReorderWait():
    void {

    if (
      this.expectedSequence <
        0 ||
        this.reorderBuffer.size ===
          0
    ) {

      return
    }

    const smallest:
      number =
      this.findSmallestBufferedSequence()

    if (
      smallest <
        0 ||
        smallest <=
          this.expectedSequence
    ) {

      return
    }

    /*
     * 已经在等待同一个缺口。
     */

    if (
      this.reorderWaitingSequence ===
        this.expectedSequence &&
        this.reorderTimer !==
          null
    ) {

      return
    }

    /*
     * 如果之前有计时器，
     * 先取消。
     */

    if (
      this.reorderTimer !==
        null
    ) {

      clearTimeout(
        this.reorderTimer
      )

      this.reorderTimer =
        null
    }

    this.reorderWaitingSequence =
      this.expectedSequence

    const waitingSequence:
      number =
      this.expectedSequence

    const reorderWaitMs:
      number =
      this.getReorderWaitMs()

    console.info(
      'WALKIE PLAYBACK: ' +
        `W23A 发现缺口 ` +
        `expected=${waitingSequence} ` +
        `next=${smallest} ` +
        `等待=${reorderWaitMs}ms`
    )

    /*
     * 注意：
     *
     * 这里不能 await。
     *
     * 让新的 UDP 包可以继续进入队列。
     */

    this.reorderTimer =
      setTimeout(
        (): void => {

          void this.resolveReorderTimeout(
            waitingSequence
          )

        },
        reorderWaitMs
      ) as number
  }

  // ============================================================
  // V24.0
  //
  // 重排等待超时
  //
  // missing=1 → PLC
  // missing>=2 → 暂不 PLC
  // ============================================================

  private async resolveReorderTimeout(
    waitingSequence:
      number
  ):
    Promise<void> {

    /*
     * ========================================================
     * 定时器结束
     * ========================================================
     */

    this.reorderTimer =
      null

    /*
     * ========================================================
     * 如果期待序号已经改变，
     * 说明缺口已经解决。
     * ========================================================
     */

    if (
      this.expectedSequence !==
        waitingSequence
    ) {

      this.reorderWaitingSequence =
        -1

      return
    }

    /*
     * ========================================================
     * 缺失包已经在等待期间到达
     *
     * 不进行 PLC。
     * ========================================================
     */

    if (
      this.reorderBuffer.has(
        waitingSequence
      )
    ) {

      this.reorderWaitingSequence =
        -1

      await this.flushReorderBuffer()

      return
    }

    /*
     * ========================================================
     * 找到目前最小的后续包
     * ========================================================
     */

    const smallest:
      number =
      this.findSmallestBufferedSequence()

    if (
      smallest < 0
    ) {

      this.reorderWaitingSequence =
        -1

      return
    }

    /*
     * 没有真正的缺口。
     */

    if (
      smallest <=
        waitingSequence
    ) {

      this.reorderWaitingSequence =
        -1

      await this.flushReorderBuffer()

      return
    }

    /*
     * ========================================================
     * 计算缺失数量
     *
     * 例如：
     *
     * expected=3509
     * next=3518
     *
     * missing=9
     *
     * ========================================================
     */

    const missing:
      number =
      smallest -
        waitingSequence

    /*
     * ========================================================
     * V24.8.4
     *
     * 连续丢 1 / 2 / 3 包：
     *
     * 1包 = PLC 20ms
     * 2包 = PLC 40ms
     * 3包 = PLC 60ms
     *
     * 不再只补 1 包。
     *
     * 超过 3 包：
     * 仍然直接跳到下一个真实包，
     * 避免长时间 PLC 产生明显机器人音。
     * ========================================================
     */

    if (
      missing >= 1 &&
        missing <= 12
    ) {

      console.warn(
        'WALKIE PLAYBACK: ' +
          `W23A ★连续丢包PLC★ ` +
          `start=${waitingSequence} ` +
          `next=${smallest} ` +
          `missing=${missing}`
      )

      for (
        let plcIndex:
          number =
          0;

        plcIndex <
          missing;

        plcIndex++
      ) {

        const plcPcm:
        ArrayBuffer | null =
          this.opus.decodePlc()

        if (
          plcPcm ===
            null ||
            plcPcm.byteLength <=
              0
        ) {

          console.warn(
            'WALKIE PLAYBACK: ' +
              `PLC失败 index=${plcIndex + 1}/${missing}`
          )

          break
        }

        const ready:
          boolean =
          await this.ensureRenderer()

        if (
          !ready
        ) {

          console.warn(
            'WALKIE PLAYBACK: ' +
              'PLC生成成功，但Renderer未就绪'
          )

          break
        }

        const plcBytes:
          Uint8Array =
          new Uint8Array(
            plcPcm
          )

        while (
          this.pcmQueue.length >=
            this.getDynamicPcmQueueLimit()
        ) {

          this.pcmQueue.shift()
        }

        this.pcmQueue.push(
          plcBytes
        )

        console.info(
          'WALKIE PLAYBACK: ' +
            `★PLC ${plcIndex + 1}/${missing}★ ` +
            `pcm=${plcPcm.byteLength}B`
        )
      }

    } else {

      console.warn(
        'WALKIE PLAYBACK: ' +
          `W23A 连续丢包过多 ` +
          `start=${waitingSequence} ` +
          `next=${smallest} ` +
          `missing=${missing} ` +
          '→ 不连续PLC'
      )
    }

    /*
     * ========================================================
     * 跳到下一个真实收到的 Sequence
     * ========================================================
     */

    this.expectedSequence =
      smallest

    this.reorderWaitingSequence =
      -1

    await this.flushReorderBuffer()

    if (
      this.reorderBuffer.size >
        0
    ) {

      this.startReorderWait()
    }
  }

  // ============================================================
  // 处理 W23A
  // ============================================================

  private async handleW23A(
    data:
      ArrayBuffer
  ):
    Promise<void> {

    const streamId:
      number =
      WalkieAudioPacket.readStreamId(
        data
      )

    const sequence:
      number =
      WalkieAudioPacket.readSequence(
        data
      )

    if (
      streamId < 0 ||
        sequence < 0
    ) {

      return
    }

    /*
     * ========================================================
     * 新 Stream
     * ========================================================
     */

    if (
      this.currentStreamId !==
        streamId
    ) {

      this.beginNewStream(
        streamId,
        sequence
      )

      /*
       * 第一包直接播放。
       */

      await this.decodeW23APacket(
        data
      )

      this.lastSequence =
        sequence

      this.expectedSequence =
        sequence + 1

      return
    }

    /*
     * ========================================================
     * Sequence 异常大跳
     * ========================================================
     */

    const jump:
      number =
      sequence -
        this.expectedSequence

    if (
      jump >
        WalkiePlayback.MAX_SEQUENCE_JUMP
    ) {

      console.warn(
        'WALKIE PLAYBACK: ' +
          `W23A Sequence 大跳跃 ` +
          `expected=${this.expectedSequence} ` +
          `received=${sequence} ` +
          `jump=${jump} ` +
          '，直接恢复当前流'
      )

      this.reorderBuffer.clear()

      if (
        this.reorderTimer !==
          null
      ) {

        clearTimeout(
          this.reorderTimer
        )

        this.reorderTimer =
          null
      }

      this.reorderWaitingSequence =
        -1

      this.expectedSequence =
        sequence

      await this.decodeW23APacket(
        data
      )

      this.lastSequence =
        sequence

      this.expectedSequence =
        sequence + 1

      return
    }

    /*
     * ========================================================
     * 旧包 / 重复包
     * ========================================================
     */

    if (
      sequence <
        this.expectedSequence
    ) {

      console.info(
        'WALKIE PLAYBACK: ' +
          `W23A 迟到/重复包丢弃 ` +
          `sequence=${sequence} ` +
          `expected=${this.expectedSequence} ` +
          '(可能是乱序包)'
      )

      return
    }

    /*
     * ========================================================
     * 正好是下一帧
     * ========================================================
     */

    if (
      sequence ===
        this.expectedSequence
    ) {

      await this.decodeW23APacket(
        data
      )

      this.lastSequence =
        sequence

      this.expectedSequence =
        sequence + 1

      /*
       * 后面的缓存包如果已经连续，
       * 立即全部播放。
       */

      await this.flushReorderBuffer()

      return
    }

    /*
     * ========================================================
     * sequence > expected
     *
     * 发现真实 Sequence GAP。
     * ========================================================
     */

    const sequenceGap:
      number =
      sequence -
        this.expectedSequence

    console.warn(
      'WALKIE PLAYBACK: ' +
        '★W23A Sequence GAP★ ' +
        `expected=${this.expectedSequence} ` +
        `received=${sequence} ` +
        `gap=${sequenceGap}`
    )

    /*
     * ========================================================
     * 加入重排缓存
     * ========================================================
     */

    if (
      !this.reorderBuffer.has(
        sequence
      )
    ) {

      this.reorderBuffer.set(
        sequence,
        data
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          `W23A 乱序缓存 ` +
          `sequence=${sequence} ` +
          `expected=${this.expectedSequence} ` +
          `buffer=${this.reorderBuffer.size}`
      )

    } else {

      console.info(
        'WALKIE PLAYBACK: ' +
          `W23A 重复缓存包忽略 ` +
          `sequence=${sequence}`
      )

      return
    }

    /*
     * 启动 15ms 重排等待。
     */

    this.startReorderWait()
  }

  // ============================================================
  // 处理一个音频包
  // ============================================================

  private async processOnePacket(
    data:
      ArrayBuffer
  ):
    Promise<void> {

    /*
     * ========================================================
     * W23A
     * ========================================================
     */

    if (
      WalkieAudioPacket.isW23A(
        data
      )
    ) {

      await this.handleW23A(
        data
      )

      return
    }

    /*
     * ========================================================
     * Legacy 裸 Opus
     * ========================================================
     */

    if (
      this.currentStreamId !==
        -1 ||
        this.reorderBuffer.size >
          0
    ) {

      this.clearReorderState()

      this.currentStreamId =
        -1

      console.info(
        'WALKIE PLAYBACK: ' +
          '切换到 Legacy Opus'
      )
    }

    if (
      !this.started
    ) {

      console.info(
        'WALKIE PLAYBACK: ' +
          `Legacy Opus length=${data.byteLength}`
      )
    }

    await this.decodeAndQueue(
      data
    )
  }

  // ============================================================
  // 开始播放
  // ============================================================

  private async startRenderer():
    Promise<void> {

    /*
     * ========================================================
     * V24.0 关键修复
     *
     * 在真正 await renderer.start() 之前，
     * 先把 started 设为 true。
     *
     * 防止多个异步流程同时调用 start()。
     *
     * 原来的问题：
     *
     *   流程 A 检查 playing=false
     *   流程 B 检查 playing=false
     *   A → renderer.start()
     *   B → renderer.start()
     *
     * 第二次 start()
     * → 6800301 illegal state
     *
     * 现在：
     *
     *   A → started=true
     *   B → 看到 started=true → return
     *
     * ========================================================
     */

    if (
      this.playing ||
        this.started
    ) {

      return
    }

    const renderer:
    audio.AudioRenderer | null =
      this.renderer

    if (
      renderer ===
        null ||
        !this.rendererReady
    ) {

      return
    }

    /*
     * ========================================================
     * 关键保护点
     *
     * 注意：
     *
     * 一定在 await 前设置。
     * ========================================================
     */

    this.started =
      true

    try {

      await renderer.start()

      this.playing =
        true

      console.info(
        'WALKIE PLAYBACK: ' +
          '★开始播放★ ' +
          `queue=${this.pcmQueue.length} ` +
          'sampleRate=16000 ' +
          'rawPCM=true ' +
          'AudioSession=VOICE_COMMUNICATION ' +
          'SessionOutput=SPEAKER ' +
          'RendererOutput=SPEAKER ' +
          'volume=1.0 ' +
          'reorder=V24.8.5 PLC=1-12-frame adaptive-buffer'
      )

    } catch (
    error
    ) {

      /*
       * start() 真失败时，
       * 恢复状态，允许下次重新启动。
       */

      this.playing =
        false

      this.started =
        false

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE PLAYBACK: ' +
          '播放启动失败 ' +
          `code=${businessError.code} ` +
          `message=${businessError.message}`
      )
    }
  }

  // ============================================================
  // 停止
  // ============================================================

  public async stop():
    Promise<void> {

    /*
     * ========================================================
     * 停止重排计时器
     * ========================================================
     */

    if (
      this.reorderTimer !==
        null
    ) {

      clearTimeout(
        this.reorderTimer
      )

      this.reorderTimer =
        null
    }

    this.reorderWaitingSequence =
      -1

    this.reorderBuffer.clear()

    /*
     * ========================================================
     * 清空 UDP 包
     * ========================================================
     */

    this.packetQueue =
      []

    /*
     * ========================================================
     * 清空 PCM
     * ========================================================
     */

    this.pcmQueue =
      []

    this.currentPcm =
      null

    this.currentPcmOffset =
      0

    /*
     * ========================================================
     * 重置 W23A
     * ========================================================
     */

    this.currentStreamId =
      -1

    this.expectedSequence =
      -1

    this.lastSequence =
      -1

    /*
     * ========================================================
     * 停止 Renderer
     * ========================================================
     */

    const renderer:
    audio.AudioRenderer | null =
      this.renderer

    if (
      renderer ===
        null
    ) {

      this.playing =
        false

      this.started =
        false

      return
    }

    try {

      await renderer.stop()

    } catch {
      // 忽略
    }

    this.playing =
      false

    this.started =
      false
  }

  // ============================================================
  // 释放
  // ============================================================

  public async release():
    Promise<void> {

    /*
     * ========================================================
     * 停止重排计时器
     * ========================================================
     */

    if (
      this.reorderTimer !==
        null
    ) {

      clearTimeout(
        this.reorderTimer
      )

      this.reorderTimer =
        null
    }

    this.reorderWaitingSequence =
      -1

    this.reorderBuffer.clear()

    /*
     * ========================================================
     * 清空队列
     * ========================================================
     */

    this.packetQueue =
      []

    this.pcmQueue =
      []

    this.currentPcm =
      null

    this.currentPcmOffset =
      0

    /*
     * ========================================================
     * 重置状态
     * ========================================================
     */

    this.currentStreamId =
      -1

    this.expectedSequence =
      -1

    this.lastSequence =
      -1

    this.packetProcessing =
      false

    this.playing =
      false

    this.started =
      false

    /*
     * ========================================================
     * Renderer
     * ========================================================
     */

    const renderer:
    audio.AudioRenderer | null =
      this.renderer

    this.renderer =
      null

    this.rendererReady =
      false

    if (
      renderer !==
        null
    ) {

      try {

        await renderer.stop()

      } catch {
        // 忽略
      }

      try {

        await renderer.release()

      } catch {
        // 忽略
      }
    }

    /*
     * ========================================================
     * AudioSession
     * ========================================================
     */

    if (
      this.audioSessionManager !==
        null &&
        this.audioSessionActive
    ) {

      try {

        await this.audioSessionManager
          .deactivateAudioSession()

        console.info(
          'WALKIE PLAYBACK: ' +
            'AudioSession 已释放'
        )

      } catch (
      error
      ) {

        const businessError:
          BusinessError =
          error as BusinessError

        console.error(
          'WALKIE PLAYBACK: ' +
            'AudioSession 释放失败 ' +
            `code=${businessError.code} ` +
            `message=${businessError.message}`
        )
      }

      this.audioSessionActive =
        false
    }

    /*
     * ========================================================
     * Decoder
     * ========================================================
     */

    this.opus.destroyDecoder()
  }

  // ============================================================
  // Sleep
  // ============================================================

  private sleep(
    milliseconds:
      number
  ):
    Promise<void> {

    return new Promise<void>(
      (
        resolve:
          () => void
      ):
        void => {

        setTimeout(
          resolve,
          milliseconds
        )
      }
    )
  }
}