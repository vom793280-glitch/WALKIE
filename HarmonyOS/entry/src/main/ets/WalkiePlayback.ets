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
 * 当前播放链路：
 *
 *   Android Opus
 *        ↓
 *   UDP
 *        ↓
 *   HarmonyOS Opus Decoder
 *        ↓
 *   16kHz / Mono / S16LE PCM
 *        ↓
 *   AudioSessionManager
 *        ↓
 *   SPEAKER
 *        ↓
 *   AudioRenderer
 *
 * 当前版本重点：
 *
 *   1. Opus 严格串行解码
 *   2. 16kHz / Mono / S16LE
 *   3. PCM 原样播放
 *   4. AudioSession 使用 VOICE_COMMUNICATION
 *   5. AudioSessionManager 强制 SPEAKER
 *   6. AudioRenderer 再指定 SPEAKER
 *   7. Renderer volume = 1.0
 *   8. 不做重采样
 *   9. 不做滤波
 *   10. 不做额外 PCM 增益
 *   11. 保留 W23A + Legacy Opus
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
  // Opus 包处理队列
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
  // W23A 流状态
  // ============================================================

  private currentStreamId:
    number =
    -1

  private lastSequence:
    number =
    -1

  // ============================================================
  // 播放参数
  // ============================================================

  private static readonly PLAYBACK_VOLUME:
    number =
    1.0

  // ============================================================
  // 初始化应用级 AudioSession
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

      /*
       * ========================================================
       * 获取 AudioManager
       * ========================================================
       */

      if (
        this.audioManager ===
          null
      ) {

        this.audioManager =
          audio.getAudioManager()
      }

      /*
       * ========================================================
       * 获取 AudioSessionManager
       * ========================================================
       */

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
       * ========================================================
       * 设置 AudioSession 场景
       *
       * 官方要求：
       *
       * AudioSessionManager 的
       * setDefaultOutputDevice()
       * 在 VOICE_COMMUNICATION 场景下生效。
       * ========================================================
       */

      sessionManager.setAudioSessionScene(
        audio.AudioSessionScene.AUDIO_SESSION_SCENE_VOICE_COMMUNICATION
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          'AudioSessionScene=VOICE_COMMUNICATION'
      )

      /*
       * ========================================================
       * 激活 AudioSession
       *
       * 允许应用级音频策略真正生效。
       * ========================================================
       */

      if (
        !this.audioSessionActive
      ) {

        const strategy:
          audio.AudioSessionStrategy = {

          concurrencyMode:
          audio.AudioConcurrencyMode.CONCURRENCY_MIX_WITH_OTHERS
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
       * ========================================================
       * ★关键★
       *
       * 应用级默认输出设备：
       *
       * SPEAKER
       *
       * AudioSessionManager 的优先级高于
       * AudioRenderer.setDefaultOutputDevice。
       * ========================================================
       */

      await sessionManager.setDefaultOutputDevice(
        audio.DeviceType.SPEAKER
      )

      console.info(
        'WALKIE PLAYBACK: ' +
          '★AudioSessionManager → SPEAKER★'
      )

      /*
       * ========================================================
       * 读取应用级默认设备
       *
       * getDefaultOutputDevice()
       * 为同步接口。
       * ========================================================
       */

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

    /*
     * ========================================================
     * 先确保应用级 AudioSession。
     * ========================================================
     */

    const sessionReady:
      boolean =
      await this.ensureAudioSession()

    if (
      !sessionReady
    ) {

      return false
    }

    /*
     * ========================================================
     * 防止多个地方同时创建 Renderer。
     * ========================================================
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

      /*
       * ========================================================
       * 音频格式
       *
       * 16000 Hz
       * Mono
       * S16LE
       * RAW
       * ========================================================
       */

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

      /*
       * ========================================================
       * Renderer 场景
       *
       * 与 Android 的 VOICE_COMMUNICATION 对齐。
       * ========================================================
       */

      const rendererInfo:
        audio.AudioRendererInfo = {

        usage:
        audio.StreamUsage.STREAM_USAGE_VOICE_COMMUNICATION,

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

      /*
       * ========================================================
       * 创建 Renderer
       * ========================================================
       */

      const renderer:
        audio.AudioRenderer =
        await audio.createAudioRenderer(
          options
        )

      this.renderer =
        renderer

      /*
       * ========================================================
       * writeData
       * ========================================================
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
       * ========================================================
       * Renderer 级别再次指定 SPEAKER
       *
       * 虽然 AudioSessionManager 优先级更高，
       * 这里仍然保留。
       *
       * 这样当前 Renderer 本身也明确要求 SPEAKER。
       * ========================================================
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
       * ========================================================
       * Renderer 音量
       *
       * 1.0 = 不衰减。
       * ========================================================
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

      /*
       * ========================================================
       * Renderer 创建完成
       * ========================================================
       */

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
  // AudioRenderer PCM 输出
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
     * 没有 PCM 时输出静音。
     */

    output.fill(
      0
    )

    let outputOffset:
      number =
      0

    /*
     * ========================================================
     * PCM 原样复制
     *
     * 不进行：
     *
     *   × 增益
     *   × 限幅
     *   × 滤波
     *   × 重采样
     *   × 格式转换
     *
     * Decoder 给什么，
     * Renderer 就播放什么。
     * ========================================================
     */

    while (
      outputOffset <
        output.length
    ) {

      /*
       * 当前 PCM 已经播放完。
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

      /*
       * ======================================================
       * 保持 S16LE 原始字节顺序。
       * ======================================================
       */

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
     * ========================================================
     * Opus 包进入串行处理队列。
     * ========================================================
     */

    this.packetQueue.push(
      data
    )

    /*
     * 网络堵塞时，
     * 丢弃最老的数据，
     * 防止延迟越来越大。
     */

    while (
      this.packetQueue.length >
        30
    ) {

      this.packetQueue.shift()
    }

    /*
     * ========================================================
     * 只允许一个处理循环。
     * ========================================================
     */

    if (
      !this.packetProcessing
    ) {

      await this.processPacketQueue()
    }
  }

  // ============================================================
  // 串行处理 Opus
  // ============================================================

  private async processPacketQueue():
    Promise<void> {

    if (
      this.packetProcessing
    ) {

      return
    }

    /*
     * 在第一次 await 前锁定。
     */

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
       * 如果处理期间来了新包，
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
       * 新语音流。
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
       * 丢掉旧包 / 重复包。
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

      if (
        !this.started
      ) {

        console.info(
          'WALKIE PLAYBACK: ' +
            `Legacy Opus length=${data.byteLength}`
        )
      }
    }

    /*
     * ========================================================
     * Opus 数据检查
     * ========================================================
     */

    if (
      opusData ===
        null ||
        opusData.byteLength <=
          0
    ) {

      return
    }

    /*
     * ========================================================
     * Opus 解码
     *
     * 已经确认：
     *
     * frames=1
     * samplesPerFrame=320
     * decodedSamples=320
     * pcmBytes=640
     *
     * ========================================================
     */

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

    /*
     * ========================================================
     * 确保 AudioRenderer
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
     * 解码后的 PCM
     * ========================================================
     */

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
     * ========================================================
     * 防止播放延迟越来越高。
     * ========================================================
     */

    while (
      this.pcmQueue.length >=
        this.pcmQueueLimit
    ) {

      this.pcmQueue.shift()
    }

    /*
     * ========================================================
     * 原始 PCM 直接入队
     * ========================================================
     */

    this.pcmQueue.push(
      pcmBytes
    )

    /*
     * ========================================================
     * 首次起播
     *
     * 当前 20ms / frame：
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
      renderer ===
        null ||
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
          '★开始播放★ ' +
          `queue=${this.pcmQueue.length} ` +
          'sampleRate=16000 ' +
          'rawPCM=true ' +
          'AudioSession=VOICE_COMMUNICATION ' +
          'SessionOutput=SPEAKER ' +
          'RendererOutput=SPEAKER ' +
          'volume=1.0'
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
     * ========================================================
     * 清空 Opus 队列
     * ========================================================
     */

    this.packetQueue =
      []

    /*
     * ========================================================
     * 清空 PCM 队列
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
     * 重置流状态
     * ========================================================
     */

    this.currentStreamId =
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
     * 重置流状态
     * ========================================================
     */

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

    /*
     * ========================================================
     * 释放 Renderer
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
     * 释放 AudioSession
     *
     * AudioSession 生命周期结束。
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