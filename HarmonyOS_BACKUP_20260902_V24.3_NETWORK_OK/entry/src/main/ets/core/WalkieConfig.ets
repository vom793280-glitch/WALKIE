export class WalkieConfig {
  static readonly VERSION: string = 'HarmonyOS V1.0'

  // ============================================================
  // VPS
  // ============================================================

  static readonly DEFAULT_SERVER_IP: string = '38.146.29.169'
  static readonly SERVER_PORT: number = 50000

  // ============================================================
  // WALKIE
  // ============================================================

  static readonly KEEP_ALIVE_INTERVAL: number = 5000
  static readonly NETWORK_PING_INTERVAL: number = 2000

  // ============================================================
  // Audio
  // ============================================================

  static readonly SAMPLE_RATE: number = 16000
  static readonly CHANNELS: number = 1
  static readonly FRAME_SAMPLES: number = 320
  static readonly FRAME_DURATION_MS: number = 20

  // ============================================================
  // W23A
  // ============================================================

  static readonly AUDIO_MAGIC: string = 'W23A'
  static readonly AUDIO_HEADER_SIZE: number = 12
  static readonly MAX_OPUS_PACKET_SIZE: number = 1208
  static readonly MAX_AUDIO_PACKET_SIZE: number =
    WalkieConfig.AUDIO_HEADER_SIZE +
      WalkieConfig.MAX_OPUS_PACKET_SIZE

  // ============================================================
  // Playback
  // ============================================================

  static readonly PLAYBACK_QUEUE_CAPACITY: number = 40
  static readonly PLAYBACK_START_BUFFER_PACKETS: number = 3
  static readonly PLAYBACK_RECOVERY_BUFFER_PACKETS: number = 3
  static readonly PLAYBACK_MAX_QUEUE_PACKETS: number = 24

  // ============================================================
  // Reconnect
  // ============================================================

  static readonly INITIAL_RECONNECT_INTERVAL: number = 300
  static readonly MAX_RECONNECT_INTERVAL: number = 1500
}