/*
 * ============================================================
 * WALKIE 音频封包
 *
 * 当前协议：
 *
 * 4 bytes  W23A
 * 4 bytes  StreamID
 * 4 bytes  Sequence
 * N bytes  Opus
 *
 * 总长度：
 * 12 + Opus长度
 *
 * 注意：
 * 这里只负责封包。
 * 不发送 UDP。
 * ============================================================
 */

export class WalkieAudioPacket {

  private static readonly HEADER_SIZE: number =
    12

  private static readonly MAGIC_W: number =
    0x57

  private static readonly MAGIC_2: number =
    0x32

  private static readonly MAGIC_3: number =
    0x33

  private static readonly MAGIC_A: number =
    0x41

  // ============================================================
  // 创建 W23A 音频包
  // ============================================================

  public static build(
    streamId: number,
    sequence: number,
    opusData: ArrayBuffer
  ): ArrayBuffer {

    const opusBytes:
      Uint8Array =
      new Uint8Array(
        opusData
      )

    const totalLength:
      number =
      WalkieAudioPacket.HEADER_SIZE +
      opusBytes.length

    const packet:
      Uint8Array =
      new Uint8Array(
        totalLength
      )

    /*
     * ========================================================
     * MAGIC
     *
     * W23A
     * ========================================================
     */

    packet[0] =
      WalkieAudioPacket.MAGIC_W

    packet[1] =
      WalkieAudioPacket.MAGIC_2

    packet[2] =
      WalkieAudioPacket.MAGIC_3

    packet[3] =
      WalkieAudioPacket.MAGIC_A

    /*
     * ========================================================
     * StreamID
     *
     * 4 bytes
     *
     * 大端序
     * ========================================================
     */

    WalkieAudioPacket.writeUint32(
      packet,
      4,
      streamId
    )

    /*
     * ========================================================
     * Sequence
     *
     * 4 bytes
     *
     * 大端序
     * ========================================================
     */

    WalkieAudioPacket.writeUint32(
      packet,
      8,
      sequence
    )

    /*
     * Opus
     */

    packet.set(
      opusBytes,
      WalkieAudioPacket.HEADER_SIZE
    )

    return packet.buffer
  }

  // ============================================================
  // 检查 W23A
  // ============================================================

  public static isW23A(
    data: ArrayBuffer
  ): boolean {

    if (
      data.byteLength <
      WalkieAudioPacket.HEADER_SIZE
    ) {

      return false
    }

    const bytes:
      Uint8Array =
      new Uint8Array(
        data
      )

    return (
      bytes[0] ===
      WalkieAudioPacket.MAGIC_W &&
      bytes[1] ===
      WalkieAudioPacket.MAGIC_2 &&
      bytes[2] ===
      WalkieAudioPacket.MAGIC_3 &&
      bytes[3] ===
      WalkieAudioPacket.MAGIC_A
    )
  }

  // ============================================================
  // 读取 StreamID
  // ============================================================

  public static readStreamId(
    data: ArrayBuffer
  ): number {

    if (
      data.byteLength <
      WalkieAudioPacket.HEADER_SIZE
    ) {

      return -1
    }

    const bytes:
      Uint8Array =
      new Uint8Array(
        data
      )

    return WalkieAudioPacket.readUint32(
      bytes,
      4
    )
  }

  // ============================================================
  // 读取 Sequence
  // ============================================================

  public static readSequence(
    data: ArrayBuffer
  ): number {

    if (
      data.byteLength <
      WalkieAudioPacket.HEADER_SIZE
    ) {

      return -1
    }

    const bytes:
      Uint8Array =
      new Uint8Array(
        data
      )

    return WalkieAudioPacket.readUint32(
      bytes,
      8
    )
  }

  // ============================================================
  // 提取 Opus
  // ============================================================

  public static extractOpus(
    data: ArrayBuffer
  ): ArrayBuffer | null {

    if (
      data.byteLength <=
      WalkieAudioPacket.HEADER_SIZE
    ) {

      return null
    }

    if (
      !WalkieAudioPacket.isW23A(
        data
      )
    ) {

      return null
    }

    const bytes:
      Uint8Array =
      new Uint8Array(
        data
      )

    const opusLength:
      number =
      bytes.length -
      WalkieAudioPacket.HEADER_SIZE

    const opus:
      Uint8Array =
      new Uint8Array(
        opusLength
      )

    opus.set(
      bytes.slice(
        WalkieAudioPacket.HEADER_SIZE
      )
    )

    return opus.buffer
  }

  // ============================================================
  // 写入 32 位整数
  // ============================================================

  private static writeUint32(
    target: Uint8Array,
    offset: number,
    value: number
  ): void {

    const safeValue:
      number =
      Math.max(
        0,
        Math.floor(
          value
        )
      ) >>> 0

    target[offset] =
      (safeValue >>> 24) &
      0xFF

    target[offset + 1] =
      (safeValue >>> 16) &
      0xFF

    target[offset + 2] =
      (safeValue >>> 8) &
      0xFF

    target[offset + 3] =
      safeValue &
      0xFF
  }

  // ============================================================
  // 读取 32 位整数
  // ============================================================

  private static readUint32(
    data: Uint8Array,
    offset: number
  ): number {

    return (
      (data[offset] * 0x1000000) +
      (data[offset + 1] << 16) +
      (data[offset + 2] << 8) +
      data[offset + 3]
    ) >>> 0
  }
}