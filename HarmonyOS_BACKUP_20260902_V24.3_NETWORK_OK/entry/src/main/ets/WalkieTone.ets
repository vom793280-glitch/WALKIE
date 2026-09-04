import { audio } from '@kit.AudioKit'
import { BusinessError } from '@kit.BasicServicesKit'

/*
 * ============================================================
 * WALKIE 抢麦成功提示音
 *
 * HarmonyOS 7.0 / API 26
 *
 * 方案：
 *
 * 直接使用 AudioRenderer 播放程序生成的 PCM。
 *
 * 不使用：
 *
 * - rawfile
 * - AVPlayer
 * - getRawFd
 * - getRawFileContent
 * - Context
 *
 * WalkieClient 原来的调用方式完全不变：
 *
 *     await this.tone.playTalkGranted()
 *
 * 效果：
 *
 *     滴
 *
 * 约 120ms
 * 2000Hz
 * 16000Hz
 * 单声道
 * 16bit
 *
 * ============================================================
 */

export class WalkieTone {

  // ============================================================
  // 当前播放器
  // ============================================================

  private player:
    audio.AudioRenderer | null =
    null

  // ============================================================
  // 当前生成位置
  //
  // 0 ～ TOTAL_SAMPLES
  // ============================================================

  private currentSample:
    number =
    0

  // ============================================================
  // 总采样数
  //
  // 16000 × 0.12 秒 = 1920
  // ============================================================

  private readonly totalSamples:
    number =
    1920

  // ============================================================
  // 每次播放状态
  // ============================================================

  private playing:
    boolean =
    false

  // ============================================================
  // 防止重复初始化
  // ============================================================

  private loading:
    Promise<boolean> | null =
    null

  // ============================================================
  // 抢麦成功提示音
  // ============================================================

  public async playTalkGranted():
    Promise<void> {

    /*
     * 防止重复触发。
     */

    if (
      this.playing
    ) {

      console.info(
        'WALKIE TONE: 已经在播放'
      )

      return
    }

    this.playing =
      true

    try {

      const ready:
        boolean =
        await this.ensurePlayer()

      if (
        !ready
      ) {

        console.error(
          'WALKIE TONE: AudioRenderer 创建失败'
        )

        return
      }

      const player:
        audio.AudioRenderer | null =
        this.player

      if (
        player === null
      ) {

        return
      }

      /*
       * 从第0个采样开始。
       */

      this.currentSample =
        0

      /*
       * 设置较高音量。
       *
       * 0.0 ～ 1.0
       */

      try {

        player.setVolume(
          1.0
        )

      } catch (
        error
      ) {

        const businessError:
          BusinessError =
          error as BusinessError

        console.error(
          'WALKIE TONE: setVolume 失败 ' +
          `code=${businessError.code} ` +
          `message=${businessError.message}`
        )
      }

      /*
       * 开始播放。
       */

      await player.start()

      console.info(
        'WALKIE TONE: AudioRenderer start'
      )

      /*
       * 120ms 提示音已经写入 renderer 缓冲后，
       * 稍微等待，让声音完整播放。
       */

      await this.sleep(
        180
      )

      /*
       * 停止播放器。
       */

      try {

        await player.stop()

      } catch {
        // 忽略
      }

      /*
       * 清空缓冲。
       */

      try {

        await player.flush()

      } catch {
        // 忽略
      }

      console.info(
        'WALKIE TONE: 滴声播放完成'
      )

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE TONE: 播放失败 ' +
        `code=${businessError.code ?? -1} ` +
        `message=${businessError.message ?? String(error)}`
      )

    } finally {

      this.playing =
        false
    }
  }

  // ============================================================
  // 创建播放器
  // ============================================================

  private async ensurePlayer():
    Promise<boolean> {

    if (
      this.player !== null
    ) {

      return true
    }

    if (
      this.loading !== null
    ) {

      return await this.loading
    }

    this.loading =
      this.createPlayer()

    try {

      return await this.loading

    } finally {

      this.loading =
        null
    }
  }

  // ============================================================
  // 创建 AudioRenderer
  // ============================================================

  private async createPlayer():
    Promise<boolean> {

    try {

      /*
       * --------------------------------------------------------
       * 音频流信息
       * --------------------------------------------------------
       *
       * 16000Hz
       * 单声道
       * 16bit little endian
       * PCM RAW
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
       * --------------------------------------------------------
       * 播放器信息
       * --------------------------------------------------------
       */

      const rendererInfo:
        audio.AudioRendererInfo = {

        usage:
          audio.StreamUsage.STREAM_USAGE_MUSIC,

        rendererFlags:
          0,

        volumeMode:
          audio.AudioVolumeMode.SYSTEM_GLOBAL
      }

      /*
       * --------------------------------------------------------
       * 播放器配置
       * --------------------------------------------------------
       */

      const options:
        audio.AudioRendererOptions = {

        streamInfo:
          streamInfo,

        rendererInfo:
          rendererInfo
      }

      /*
       * --------------------------------------------------------
       * 创建 AudioRenderer
       * --------------------------------------------------------
       */

      const player:
        audio.AudioRenderer =
        await audio.createAudioRenderer(
          options
        )

      this.player =
        player

      console.info(
        'WALKIE TONE: AudioRenderer 创建成功'
      )

      /*
       * --------------------------------------------------------
       * 注册 writeData
       * --------------------------------------------------------
       *
       * AudioRenderer 播放 PCM 时会不断要求数据。
       */

      player.on(
        'writeData',
        (
          buffer:
            ArrayBuffer
        ): void => {

          this.fillPcmBuffer(
            buffer
          )
        }
      )

      /*
       * --------------------------------------------------------
       * 错误监听
       * --------------------------------------------------------
       */


      return true

    } catch (
      error
    ) {

      const businessError:
        BusinessError =
        error as BusinessError

      console.error(
        'WALKIE TONE: 创建 AudioRenderer 失败 ' +
        `code=${businessError.code ?? -1} ` +
        `message=${businessError.message ?? String(error)}`
      )

      this.player =
        null

      return false
    }
  }

  // ============================================================
  // 填充 PCM
  // ============================================================

  private fillPcmBuffer(
    buffer:
      ArrayBuffer
  ):
    void {

    /*
     * 16bit = 2 bytes / sample
     */

    const view:
      DataView =
      new DataView(
        buffer
      )

    const sampleCount:
      number =
      Math.floor(
        buffer.byteLength / 2
      )

    /*
     * 2000Hz
     */

    const frequency:
      number =
      2000

    /*
     * 音量 85%
     */

    const volume:
      number =
      0.55

    /*
     * 16000Hz
     */

    const sampleRate:
      number =
      16000

    for (
      let index = 0;
      index < sampleCount;
      index++
    ) {

      /*
       * 已经超过120ms：
       *
       * 填充静音。
       */

      if (
        this.currentSample >=
        this.totalSamples
      ) {

        view.setInt16(
          index * 2,
          0,
          true
        )

        continue
      }

      /*
       * 产生2000Hz正弦波。
       */

      const time:
        number =
        this.currentSample /
        sampleRate

      const sample:
        number =
        Math.sin(
          2 *
          Math.PI *
          frequency *
          time
        ) *
        volume

      const value:
        number =
        Math.max(
          -32768,
          Math.min(
            32767,
            Math.round(
              sample *
              32767
            )
          )
        )

      view.setInt16(
        index * 2,
        value,
        true
      )

      this.currentSample++
    }
  }

  // ============================================================
  // 延时
  // ============================================================

  private sleep(
    milliseconds:
      number
  ):
    Promise<void> {

    return new Promise<void>(
      (
        resolve
      ): void => {

        setTimeout(
          () => {

            resolve()

          },
          milliseconds
        )
      }
    )
  }

  // ============================================================
  // 释放
  // ============================================================

  public async release():
    Promise<void> {

    const player:
      audio.AudioRenderer | null =
      this.player

    this.player =
      null

    this.playing =
      false

    this.currentSample =
      0

    this.loading =
      null

    if (
      player === null
    ) {

      return
    }

    try {

      await player.stop()

    } catch {
      // 忽略
    }

    try {

      await player.flush()

    } catch {
      // 忽略
    }

    try {

      await player.release()

    } catch {
      // 忽略
    }

    console.info(
      'WALKIE TONE: AudioRenderer 已释放'
    )
  }
}