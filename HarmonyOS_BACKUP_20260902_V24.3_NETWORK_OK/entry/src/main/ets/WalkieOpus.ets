import walkieOpus from 'libwalkieopus.so'


/*
 * ============================================================
 * WALKIE Native Opus / Native OHAudio
 *
 * V24.0
 *
 * HarmonyOS 7.0 / API 26
 *
 * 同一个 libwalkieopus.so 同时提供：
 *
 *   1. Opus Encoder
 *   2. Opus Decoder
 *   3. Opus PLC
 *   4. Native OHAudio Capturer
 * ============================================================
 */

export class WalkieOpus {

  private encoderReady: boolean =
    false

  private decoderReady: boolean =
    false

  private captureReady: boolean =
    false

  // ============================================================
  // Opus Encoder
  // ============================================================

  public createEncoder(): boolean {

    if (
      this.encoderReady
    ) {

      return true
    }

    try {

      const result: boolean =
        walkieOpus.createEncoder()

      if (
        result
      ) {

        this.encoderReady =
          true

        console.info(
          'WALKIE OPUS: Encoder 创建成功'
        )

        return true
      }

    } catch {
      // 统一返回 false
    }

    console.error(
      'WALKIE OPUS: Encoder 创建失败'
    )

    return false
  }

  // ============================================================
  // Opus Encode
  // ============================================================

  public encode(
    pcm:
      ArrayBuffer
  ):
    ArrayBuffer | null {

    if (
      !this.encoderReady
    ) {

      const ready:
        boolean =
        this.createEncoder()

      if (
        !ready
      ) {

        return null
      }
    }

    if (
      pcm.byteLength !==
        640
    ) {

      console.error(
        'WALKIE OPUS: PCM大小错误=' +
          pcm.byteLength
      )

      return null
    }

    try {

      const result:
        ArrayBuffer | null =
        walkieOpus.encode(
          pcm
        )

      return result

    } catch {

      console.error(
        'WALKIE OPUS: 编码失败'
      )

      return null
    }
  }

  // ============================================================
  // Destroy Encoder
  // ============================================================

  public destroyEncoder():
    void {

    if (
      !this.encoderReady
    ) {

      return
    }

    try {

      walkieOpus.destroyEncoder()

    } catch {
      // 忽略释放异常
    }

    this.encoderReady =
      false
  }

  // ============================================================
  // Opus Decoder
  // ============================================================

  public createDecoder():
    boolean {

    if (
      this.decoderReady
    ) {

      return true
    }

    try {

      const result:
        boolean =
        walkieOpus.createDecoder()

      if (
        result
      ) {

        this.decoderReady =
          true

        console.info(
          'WALKIE OPUS: Decoder 创建成功'
        )

        return true
      }

    } catch {
      // 统一返回 false
    }

    console.error(
      'WALKIE OPUS: Decoder 创建失败'
    )

    return false
  }

  // ============================================================
  // Opus Decode
  // ============================================================

  public decode(
    opusData:
      ArrayBuffer
  ):
    ArrayBuffer | null {

    if (
      !this.decoderReady
    ) {

      const ready:
        boolean =
        this.createDecoder()

      if (
        !ready
      ) {

        return null
      }
    }

    if (
      opusData.byteLength <=
        0
    ) {

      return null
    }

    if (
      opusData.byteLength >
        1208
    ) {

      return null
    }

    try {

      const result:
        ArrayBuffer | null =
        walkieOpus.decode(
          opusData
        )

      return result

    } catch {

      console.error(
        'WALKIE OPUS: 解码失败'
      )

      return null
    }
  }

  // ============================================================
  // V24.0 Opus PLC
  //
  // 只在上层已经确认：
  //
  //     missing == 1
  //
  // 时调用。
  //
  // 输出：
  //
  //     320 samples
  //     640 bytes PCM
  // ============================================================

  public decodePlc():
    ArrayBuffer | null {

    if (
      !this.decoderReady
    ) {

      const ready:
        boolean =
        this.createDecoder()

      if (
        !ready
      ) {

        return null
      }
    }

    try {

      const result:
        ArrayBuffer | null =
        walkieOpus.decodePlc()

      return result

    } catch {

      console.error(
        'WALKIE OPUS: PLC 解码失败'
      )

      return null
    }
  }

  // ============================================================
  // Destroy Decoder
  // ============================================================

  public destroyDecoder():
    void {

    if (
      !this.decoderReady
    ) {

      return
    }

    try {

      walkieOpus.destroyDecoder()

    } catch {
      // 忽略
    }

    this.decoderReady =
      false
  }

  // ============================================================
  // Native OHAudio Start
  // ============================================================

  public startCapture():
    boolean {

    if (
      this.captureReady
    ) {

      return true
    }

    try {

      const result:
        boolean =
        walkieOpus.startCapture()

      if (
        result
      ) {

        this.captureReady =
          true

        console.info(
          'WALKIE OHAUDIO: ' +
            '★Native Capturer 启动成功★'
        )

        return true
      }

    } catch {
      // 统一返回 false
    }

    console.error(
      'WALKIE OHAUDIO: ' +
        'Native Capturer 启动失败'
    )

    return false
  }

  // ============================================================
  // Native OHAudio Stop
  // ============================================================

  public stopCapture():
    void {

    if (
      !this.captureReady
    ) {

      return
    }

    try {

      walkieOpus.stopCapture()

    } catch {
      // 忽略
    }

    this.captureReady =
      false

    console.info(
      'WALKIE OHAUDIO: ' +
        'Native Capturer 已停止'
    )
  }

  // ============================================================
  // Native OHAudio Release
  // ============================================================

  public releaseCapture():
    void {

    try {

      walkieOpus.releaseCapture()

    } catch {
      // 忽略
    }

    this.captureReady =
      false
  }

  // ============================================================
  // 统一释放
  // ============================================================

  public destroy():
    void {

    this.stopCapture()

    this.destroyEncoder()

    this.destroyDecoder()
  }

  // ============================================================
  // 固定参数
  // ============================================================

  public getSampleRate():
    number {

    return 16000
  }

  public getChannels():
    number {

    return 1
  }

  public getFrameSamples():
    number {

    return 320
  }

  public getFrameBytes():
    number {

    return 640
  }

  public getFrameDurationMs():
    number {

    return 20
  }
}