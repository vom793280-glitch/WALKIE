import math
import wave
import struct

output_file = "walkie_talk_ok.wav"

sample_rate = 16000
duration = 0.12
frequency = 2000.0
volume = 0.85

frames = []

for i in range(int(sample_rate * duration)):
    t = i / sample_rate

    sample = math.sin(
        2.0 * math.pi * frequency * t
    )

    sample *= volume

    value = int(
        sample * 32767
    )

    value = max(
        -32768,
        min(
            32767,
            value
        )
    )

    frames.append(
        struct.pack("<h", value)
    )

with wave.open(output_file, "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(sample_rate)
    wav.writeframes(b"".join(frames))

print("生成完成：", output_file)