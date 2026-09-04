export function createEncoder(): boolean

export function encode(
  pcm: ArrayBuffer
): ArrayBuffer | null

export function destroyEncoder(): void

export function createDecoder(): boolean

export function decode(
  opusData: ArrayBuffer
): ArrayBuffer | null

export function decodePlc(): ArrayBuffer | null

export function destroyDecoder(): void

export function startCapture(): boolean

export function stopCapture(): void

export function releaseCapture(): void

export function getPcmStats(): bigint