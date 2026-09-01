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
 * Android → HarmonyOS 播放
 *
 * 支持：
 *
 * 1. W23A
 *    W23A + StreamID + Sequence + Opus
 *
 * 2. Legacy
 *    直接 Opus
 *
 * 音频：
 *
 * 16000 Hz
 * Mono
 * S16LE
 * RAW
 *
 * 当前版本重点：
 *
 * 1. Opus 包严格按到达顺序处理
 * 2. 禁止多个 playPacket() 并发解码
 * 3. 减少语音帧乱序导致的模糊
 * 4. 保留当前已经合适的 1.6 倍音量
 * 5. 使用软限幅
 * 6. 保留 W23A + Legacy Opus
 *
 * ============================================================
 */

export class WalkiePlayback {

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

  private readonly pcmQueueLimit:
    number =
    12

  private currentPcm:
  Uint8Array | null =
    null

  private currentPcmOffset:
    number =
    0

  // ============================================================
  // 音频包处理队列
  // ============================================================

  /*
   * 这里专门存放收到的 Opus 包。
   *
   * 不直接在 UDP 回调里并发执行 decode。
   *
   * 例如：
   *
   * 包1
   * 包2
   * 包3
   * 包4
   *
   * 必须：
   *
   * 1 → 2 → 3 → 4
   *
   * 严格顺序处理。
   */

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
  // W23A 流状态
  // ============================================================

  private currentStreamId:
    number =
    -1

  private lastSequence:
    number =
    -1

  // ============================================================
  // 播放增益
  // ============================================================

  private static readonly PLAYBACK_GAIN:
    number =
    1.6

  // ============================================================
  // PCM 范围
  // ============================================================

  private static readonly PCM_MAX:
    number =
    32767

  private static readonly PCM_MIN:
    number =
    -32768

  // ============================================================
  // 软限幅起点
  // ============================================================

  private static readonly SOFT_LIMIT_START:
    number =
    26000

  // ============================================================
  // Renderer 创建
  // ============================================================

  private async ensureRenderer():
    Promise<boolean> {

    if (
      this.renderer !== null &&
        this.rendererReady
    ) {

      return true
    }

    if (
      this.rendererStarting
    ) {

      /*
       * 已经有人正在创建播放器。
       *
       * 等待一下再重新检查。
       */

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
        audio.AudioSamplingRate.SAMPLE_RATE_16000,

        channels:
        audio.AudioChannel.CHANNEL_1,

        sampleFormat:
        audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,

        encodingType:
        audio.AudioEncodingType.ENCODING_TYPE_RAW
      }

      const rendererInfo:
        audio.AudioRendererInfo = {

        /*
         * 这是实时语音消息/对讲场景。
         *
         * 不使用 VOICE_COMMUNICATION，
         * 因为该通路更偏 VoIP 通话，
         * 系统可能优先路由到听筒。
         *
         * VOICE_MESSAGE 更适合当前这种：
         *
         * Android → 鸿蒙
         * 实时语音消息/对讲
         * 同时保持扬声器播放。
         */

        usage:
        audio.StreamUsage.STREAM_USAGE_VOICE_MESSAGE,

        rendererFlags:
        0,

        volumeMode:
        audio.AudioVolumeMode.SYSTEM_GLOBAL
      }

      const options:
        audio.AudioRendererOptions = {

        streamInfo:
        streamInfo,

        rendererInfo:
        rendererInfo
      }

      const renderer:
        audio.AudioRenderer =
        await audio.createAudioRenderer(
          options
        )

      this.renderer =
        renderer

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

      this.rendererReady =
        true

      console.info(
        'WALKIE PLAYBACK: ' +
          'AudioRenderer 创建成功'
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
  // AudioRenderer 输出
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

    /*
     * 默认静音。
     */

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

      /*
       * 当前 PCM 播完：
       * 从 PCM 队列拿下一块。
       */

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
          this.currentPcm === null
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

      const sourceStart:
        number =
        this.currentPcmOffset

      /*
       * 正常情况下：
       *
       * S16LE
       * 2 字节 = 1 sample
       */

      if (
        (sourceStart & 1) !== 0 ||
          (copyLength & 1) !== 0
      ) {

        /*
         * 异常字节对齐时不处理增益，
         * 避免破坏 PCM。
         */

        output.set(
          current.subarray(
            sourceStart,
            sourceStart +
              copyLength
          ),
          outputOffset
        )

      } else {

        for (
          let index:
            number =
            0;
          index < copyLength;
          index += 2
        ) {

          const sourceIndex:
            number =
            sourceStart +
              index

          const low:
            number =
            current[
            sourceIndex
            ]

          const high:
            number =
            current[
            sourceIndex +
              1
            ]

          let sample:
            number =
            (
              low |
                (
                  high <<
                    8
                )
            )

          /*
           * 转有符号 16bit。
           */

          if (
            sample >=
              32768
          ) {

            sample -=
            65536
          }

          /*
           * 音量增益。
           *
           * 当前用户测试：
           * 1.6 倍音量合适。
           */

          sample *=
          WalkiePlayback.PLAYBACK_GAIN

          /*
           * ====================================================
           * 软限幅
           *
           * 避免人声峰值直接硬裁剪。
           * ====================================================
           */

          const sign:
            number =
            sample < 0
              ? -1
              : 1

          let magnitude:
            number =
            Math.abs(
              sample
            )

          const softStart:
            number =
            WalkiePlayback.SOFT_LIMIT_START

          const maxValue:
            number =
            WalkiePlayback.PCM_MAX

          if (
            magnitude >
              softStart
          ) {

            const over:
              number =
              magnitude -
                softStart

            const range:
              number =
              maxValue -
                softStart

            magnitude =
              softStart +
                (
                  over *
                    range
                ) /
                  (
                    over +
                      range
                  )
          }

          let finalSample:
            number =
            sign *
              magnitude

          if (
            finalSample >
              WalkiePlayback.PCM_MAX
          ) {

            finalSample =
              WalkiePlayback.PCM_MAX
          }

          if (
            finalSample <
              WalkiePlayback.PCM_MIN
          ) {

            finalSample =
              WalkiePlayback.PCM_MIN
          }

          const integerSample:
            number =
            Math.round(
              finalSample
            )

          const unsignedSample:
            number =
            integerSample < 0
              ? integerSample +
              65536
              : integerSample

          output[
          outputOffset +
            index
          ] =
            unsignedSample &
              0xFF

          output[
          outputOffset +
            index +
            1
          ] =
            (
              unsignedSample >>
                8
            ) &
              0xFF
        }
      }

      outputOffset +=
      copyLength

      this.currentPcmOffset +=
      copyLength
    }
  }

  // ============================================================
  // 接收音频包
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

    /*
     * 加入待处理队列。
     */

    this.packetQueue.push(
      data
    )

    /*
     * 防止队列无限增长。
     *
     * 如果网络瞬间拥塞，
     * 保留最新语音。
     */

    while (
      this.packetQueue.length >
        30
    ) {

      this.packetQueue.shift()
    }

    /*
     * 启动唯一的处理循环。
     *
     * 注意：
     *
     * 不使用 Promise.then 链，
     * 避免 ArkTS 的
     * any / unknown 推断问题。
     */

    if (
      !this.packetProcessing
    ) {

      await this.processPacketQueue()
    }
  }

  // ============================================================
  // 串行处理音频包
  // ============================================================

  private async processPacketQueue():
    Promise<void> {

    /*
     * 先抢占处理权。
     *
     * 在第一个 await 之前设置，
     * 防止第二个 playPacket() 再启动第二个循环。
     */

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
       * 如果 finally 时又来了新包，
       * 再启动一次处理。
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
  // 处理一个音频包
  // ============================================================

  private async processOnePacket(
    data:
      ArrayBuffer
  ):
    Promise<void> {

    let opusData:
    ArrayBuffer | null =
      null

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
       * 新音频流。
       */

      if (
        this.currentStreamId !==
          streamId
      ) {

        this.currentStreamId =
          streamId

        this.lastSequence =
          -1

        this.pcmQueue =
          []

        this.currentPcm =
          null

        this.currentPcmOffset =
          0

        console.info(
          'WALKIE PLAYBACK: ' +
            `W23A新音频流 stream=${streamId}`
        )
      }

      /*
       * 丢弃重复/旧包。
       */

      if (
        this.lastSequence >=
          0 &&
          sequence <=
            this.lastSequence
      ) {

        return
      }

      this.lastSequence =
        sequence

      opusData =
        WalkieAudioPacket.extractOpus(
          data
        )

    } else {

      /*
       * ========================================================
       * Legacy 裸 Opus
       * ========================================================
       */

      opusData =
        data

      /*
       * 不每包打印日志，
       * 避免实时语音时日志过多。
       *
       * 每收到第一个 Legacy 包时打印一次。
       */

      if (
        !this.started
      ) {

        console.info(
          'WALKIE PLAYBACK: ' +
            `Legacy Opus length=${data.byteLength}`
        )
      }
    }

    if (
      opusData === null ||
        opusData.byteLength <=
          0
    ) {

      return
    }

    /*
     * ========================================================
     * Opus 解码
     *
     * 串行执行。
     * ========================================================
     */

    const pcmData:
    ArrayBuffer | null =
      this.opus.decode(
        opusData
      )

    if (
      pcmData === null
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

    /*
     * ========================================================
     * 创建播放器
     * ========================================================
     */

    const ready:
      boolean =
      await this.ensureRenderer()

    if (
      !ready
    ) {

      return
    }

    /*
     * ========================================================
     * PCM 入队
     * ========================================================
     */

    const pcmBytes:
      Uint8Array =
      new Uint8Array(
        pcmData
      )

    /*
     * 防止播放延迟无限增加。
     *
     * 队列满时丢最旧帧，
     * 保留最新声音。
     */

    while (
      this.pcmQueue.length >=
        this.pcmQueueLimit
    ) {

      this.pcmQueue.shift()
    }

    this.pcmQueue.push(
      pcmBytes
    )

    /*
     * ========================================================
     * 首次起播：
     *
     * 3 帧 ≈ 60ms
     * ========================================================
     */

    if (
      !this.started &&
        this.pcmQueue.length >=
          3
    ) {

      await this.startRenderer()
    }
  }

  // ============================================================
  // 开始播放
  // ============================================================

  private async startRenderer():
    Promise<void> {

    if (
      this.playing
    ) {

      return
    }

    const renderer:
    audio.AudioRenderer | null =
      this.renderer

    if (
      renderer === null ||
        !this.rendererReady
    ) {

      return
    }

    try {

      await renderer.start()

      this.playing =
        true

      this.started =
        true

      console.info(
        'WALKIE PLAYBACK: ' +
          `★开始播放★ ` +
          `queue=${this.pcmQueue.length} ` +
          `gain=${WalkiePlayback.PLAYBACK_GAIN}`
      )

    } catch (
    error
    ) {

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
     * 清空待处理 Opus。
     */

    this.packetQueue =
      []

    /*
     * 清空 PCM。
     */

    this.pcmQueue =
      []

    this.currentPcm =
      null

    this.currentPcmOffset =
      0

    /*
     * 重置 W23A。
     */

    this.currentStreamId =
      -1

    this.lastSequence =
      -1

    /*
     * 防止旧处理状态影响下一次讲话。
     */

    this.packetProcessing =
      false

    const renderer:
    audio.AudioRenderer | null =
      this.renderer

    if (
      renderer === null
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

    this.packetQueue =
      []

    this.pcmQueue =
      []

    this.currentPcm =
      null

    this.currentPcmOffset =
      0

    this.currentStreamId =
      -1

    this.lastSequence =
      -1

    this.packetProcessing =
      false

    this.playing =
      false

    this.started =
      false

    const renderer:
    audio.AudioRenderer | null =
      this.renderer

    this.renderer =
      null

    this.rendererReady =
      false

    if (
      renderer !== null
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