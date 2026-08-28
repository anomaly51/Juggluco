#!/usr/bin/env python3
"""Generate Juggluco's original built-in alert sound library.

The clips are synthesized from basic waveforms and deterministic noise. They
contain no sampled or third-party material. Output is mono Ogg/Vorbis so the
Android resources remain small while still decoding reliably on minSdk 21.
"""

from __future__ import annotations

from array import array
import json
import math
from pathlib import Path
import random
import shutil
import subprocess


SAMPLE_RATE = 48_000
TAU = 2.0 * math.pi
TARGET_LOUDNESS_LUFS = -16.0
# Leave codec headroom: Vorbis reconstruction can overshoot the PCM peak.
TARGET_TRUE_PEAK_DB = -2.5
OUTPUT = (Path(__file__).resolve().parents[1] / "Common" / "src" / "main"
          / "res" / "raw")


def blank(seconds: float) -> list[float]:
    return [0.0] * int(round(seconds * SAMPLE_RATE))


def _envelope(t: float, duration: float, attack: float,
              release: float) -> float:
    if t < 0.0 or t >= duration:
        return 0.0
    lead = min(1.0, t / max(attack, 1e-6))
    tail = min(1.0, (duration - t) / max(release, 1e-6))
    return max(0.0, min(lead, tail))


def _wave(phase: float, kind: str) -> float:
    sine = math.sin(phase)
    if kind == "triangle":
        return (2.0 / math.pi) * math.asin(sine)
    if kind == "square":
        return math.tanh(3.2 * sine)
    if kind == "saw":
        cycle = phase / TAU
        return 2.0 * (cycle - math.floor(cycle + 0.5))
    return sine


def add_osc(track: list[float], start: float, duration: float,
            frequency: float, amplitude: float, *, end_frequency: float | None = None,
            kind: str = "sine", attack: float = 0.012,
            release: float = 0.08, decay: float = 0.0,
            vibrato_hz: float = 0.0, vibrato_depth: float = 0.0) -> None:
    begin = max(0, int(start * SAMPLE_RATE))
    count = min(int(duration * SAMPLE_RATE), len(track) - begin)
    if count <= 0:
        return
    end_frequency = frequency if end_frequency is None else end_frequency
    sweep = (end_frequency - frequency) / max(duration, 1e-6)
    for offset in range(count):
        t = offset / SAMPLE_RATE
        phase = TAU * (frequency * t + 0.5 * sweep * t * t)
        if vibrato_hz and vibrato_depth:
            phase += vibrato_depth * math.sin(TAU * vibrato_hz * t)
        env = _envelope(t, duration, attack, release)
        if decay:
            env *= math.exp(-decay * t / max(duration, 1e-6))
        track[begin + offset] += amplitude * env * _wave(phase, kind)


def add_bell(track: list[float], start: float, frequency: float,
             duration: float = 1.3, amplitude: float = 0.55) -> None:
    partials = ((1.0, 1.0, 4.0), (2.01, 0.42, 5.0),
                (3.87, 0.22, 6.2), (5.42, 0.10, 7.0))
    for ratio, level, decay in partials:
        add_osc(track, start, duration, frequency * ratio,
                amplitude * level, attack=0.004, release=0.16, decay=decay)


def add_pluck(track: list[float], start: float, frequency: float,
              duration: float = 0.75, amplitude: float = 0.48,
              wooden: bool = False) -> None:
    partials = ((1, 1.0), (2, 0.36), (3, 0.18), (4, 0.08))
    for harmonic, level in partials:
        add_osc(track, start, duration, frequency * harmonic,
                amplitude * level, kind="triangle" if wooden else "sine",
                attack=0.003, release=0.08, decay=5.0 + harmonic)


def add_pad(track: list[float], start: float, duration: float,
            frequencies: tuple[float, ...], amplitude: float = 0.18) -> None:
    for index, frequency in enumerate(frequencies):
        add_osc(track, start, duration, frequency, amplitude,
                attack=0.28, release=0.38, vibrato_hz=4.0 + index * 0.4,
                vibrato_depth=0.8)
        add_osc(track, start, duration, frequency * 2.0, amplitude * 0.12,
                attack=0.28, release=0.38)


def add_noise_burst(track: list[float], start: float, duration: float,
                    amplitude: float, seed: int, smooth: float = 0.72,
                    decay: float = 5.0) -> None:
    begin = max(0, int(start * SAMPLE_RATE))
    count = min(int(duration * SAMPLE_RATE), len(track) - begin)
    rng = random.Random(seed)
    filtered = 0.0
    for offset in range(max(0, count)):
        t = offset / SAMPLE_RATE
        filtered = smooth * filtered + (1.0 - smooth) * rng.uniform(-1.0, 1.0)
        env = _envelope(t, duration, 0.002, min(0.08, duration / 2.0))
        env *= math.exp(-decay * t / max(duration, 1e-6))
        track[begin + offset] += amplitude * env * filtered


def add_fm(track: list[float], start: float, duration: float,
           carrier: float, modulator: float, index: float,
           amplitude: float) -> None:
    begin = max(0, int(start * SAMPLE_RATE))
    count = min(int(duration * SAMPLE_RATE), len(track) - begin)
    for offset in range(max(0, count)):
        t = offset / SAMPLE_RATE
        env = _envelope(t, duration, 0.025, 0.12)
        phase = TAU * carrier * t + index * math.sin(TAU * modulator * t)
        track[begin + offset] += amplitude * env * math.sin(phase)


def urgent_pulse() -> list[float]:
    track = blank(3.6)
    for start in (0.0, 0.9, 1.8, 2.7):
        add_osc(track, start, 0.34, 820, 0.62, kind="square", release=0.035)
        add_osc(track, start + 0.39, 0.34, 610, 0.58,
                kind="square", release=0.035)
    return track


def air_horn() -> list[float]:
    track = blank(3.45)
    for start in (0.0, 1.13, 2.26):
        add_osc(track, start, 0.82, 205, 0.55, end_frequency=222,
                kind="saw", attack=0.045, release=0.14)
        add_osc(track, start, 0.82, 258, 0.42, end_frequency=277,
                kind="saw", attack=0.045, release=0.14)
    return track


def rapid_beacon() -> list[float]:
    track = blank(3.4)
    for index in range(12):
        start = index * 0.27
        frequency = 1180 if index % 2 == 0 else 880
        add_osc(track, start, 0.13, frequency, 0.72,
                kind="square", attack=0.004, release=0.025)
    return track


def rising_alarm() -> list[float]:
    track = blank(3.7)
    for start in (0.0, 0.92, 1.84, 2.76):
        add_osc(track, start, 0.68, 360, 0.72, end_frequency=1320,
                kind="triangle", attack=0.015, release=0.08)
    return track


def double_knock() -> list[float]:
    track = blank(3.6)
    for group in (0.0, 1.2, 2.4):
        for offset in (0.0, 0.23):
            start = group + offset
            add_noise_burst(track, start, 0.16, 0.75,
                            int(start * 1000) + 17, smooth=0.84, decay=8.0)
            add_osc(track, start, 0.24, 155, 0.68,
                    attack=0.002, release=0.06, decay=7.0)
    return track


def crystal_bells() -> list[float]:
    track = blank(4.1)
    for start, note in zip((0.0, 0.48, 0.96, 1.55, 2.05, 2.55),
                           (1046.5, 1318.5, 1568.0, 1318.5, 1760.0, 1568.0)):
        add_bell(track, start, note, 1.35, 0.45)
    return track


def door_chime() -> list[float]:
    track = blank(4.0)
    for start in (0.0, 2.0):
        add_bell(track, start, 987.8, 1.5, 0.52)
        add_bell(track, start + 0.46, 659.3, 1.6, 0.50)
    return track


def temple_bowl() -> list[float]:
    track = blank(4.8)
    for start in (0.0, 2.35):
        add_bell(track, start, 392.0, 2.2, 0.60)
        add_osc(track, start, 2.2, 404.0, 0.15,
                attack=0.006, release=0.3, decay=2.4)
    return track


def glass_drops() -> list[float]:
    track = blank(4.0)
    notes = (1396.9, 1760.0, 1174.7, 2093.0, 1568.0, 1318.5, 1975.5)
    starts = (0.0, 0.43, 0.91, 1.35, 1.92, 2.48, 3.02)
    for start, note in zip(starts, notes):
        add_bell(track, start, note, 0.88, 0.34)
    return track


def music_box() -> list[float]:
    track = blank(4.25)
    notes = (659.3, 784.0, 987.8, 784.0, 880.0, 1046.5, 987.8, 784.0)
    for index, note in enumerate(notes):
        add_pluck(track, index * 0.46, note, 0.68, 0.43)
    return track


def wind_chimes() -> list[float]:
    track = blank(4.7)
    events = ((0.0, 880.0), (0.36, 1318.5), (0.72, 1046.5),
              (1.33, 1568.0), (1.72, 987.8), (2.28, 1760.0),
              (2.74, 1174.7), (3.31, 1396.9), (3.72, 1046.5))
    for start, note in events:
        add_bell(track, start, note, 1.15, 0.32)
    return track


def sonar_ping() -> list[float]:
    track = blank(4.2)
    for start in (0.0, 1.05, 2.1, 3.15):
        add_osc(track, start, 0.75, 1320, 0.75,
                end_frequency=1160, attack=0.003, release=0.22, decay=4.5)
        add_osc(track, start, 0.75, 660, 0.25,
                attack=0.003, release=0.22, decay=5.0)
    return track


def radar_sweep() -> list[float]:
    track = blank(4.0)
    for start in (0.0, 1.0, 2.0, 3.0):
        add_osc(track, start, 0.82, 280, 0.60, end_frequency=1900,
                kind="sine", attack=0.02, release=0.08)
    return track


def pixel_jump() -> list[float]:
    track = blank(3.8)
    notes = (440.0, 659.3, 880.0, 1318.5, 880.0, 659.3)
    for group in (0.0, 1.9):
        for index, note in enumerate(notes):
            add_osc(track, group + index * 0.27, 0.18, note, 0.52,
                    kind="square", attack=0.003, release=0.025)
    return track


def retro_game() -> list[float]:
    track = blank(4.2)
    notes = (523.3, 659.3, 784.0, 1046.5, 784.0, 987.8, 659.3, 784.0,
             523.3, 392.0, 523.3, 659.3)
    for index, note in enumerate(notes):
        add_osc(track, index * 0.31, 0.22, note, 0.48,
                kind="square", attack=0.003, release=0.035)
        add_osc(track, index * 0.31, 0.22, note / 2.0, 0.18,
                kind="triangle", attack=0.003, release=0.035)
    return track


def signal_code() -> list[float]:
    track = blank(4.1)
    pattern = (1, 1, 3, 1, 3, 3, 1, 1, 1)
    cursor = 0.0
    for units in pattern:
        duration = 0.14 * units
        add_osc(track, cursor, duration, 920, 0.68,
                kind="triangle", attack=0.004, release=0.025)
        cursor += duration + 0.12
    return track


def neon_wave() -> list[float]:
    track = blank(4.0)
    for start, carrier in ((0.0, 430), (0.72, 570), (1.44, 760),
                           (2.16, 570), (2.88, 860)):
        add_fm(track, start, 0.58, carrier, 74, 5.2, 0.58)
    return track


def bright_marimba() -> list[float]:
    track = blank(4.2)
    notes = (523.3, 659.3, 784.0, 1046.5, 784.0, 659.3, 880.0, 1046.5)
    for index, note in enumerate(notes):
        add_pluck(track, index * 0.46, note, 0.62, 0.52, wooden=True)
    return track


def piano_steps() -> list[float]:
    track = blank(4.4)
    chords = ((261.6, 329.6, 392.0), (329.6, 392.0, 493.9),
              (392.0, 493.9, 587.3), (523.3, 659.3, 784.0))
    for index, chord in enumerate(chords):
        for note in chord:
            add_pluck(track, index * 0.95, note, 1.05, 0.27)
    return track


def sunrise() -> list[float]:
    track = blank(4.8)
    add_pad(track, 0.0, 2.5, (261.6, 329.6, 392.0), 0.20)
    add_pad(track, 2.15, 2.6, (329.6, 415.3, 493.9), 0.21)
    add_bell(track, 3.15, 1046.5, 1.35, 0.30)
    return track


def major_arpeggio() -> list[float]:
    track = blank(4.0)
    notes = (523.3, 659.3, 784.0, 1046.5, 784.0, 659.3, 523.3)
    for index, note in enumerate(notes):
        add_pluck(track, index * 0.48, note, 0.8, 0.47)
    return track


def minor_arpeggio() -> list[float]:
    track = blank(4.0)
    notes = (440.0, 523.3, 659.3, 880.0, 659.3, 523.3, 440.0)
    for index, note in enumerate(notes):
        add_pluck(track, index * 0.48, note, 0.82, 0.47)
    return track


def soft_pop() -> list[float]:
    track = blank(4.0)
    for index, start in enumerate((0.0, 0.63, 1.24, 2.02, 2.64, 3.25)):
        add_osc(track, start, 0.28, 520 + index * 48, 0.48,
                end_frequency=260 + index * 22, attack=0.003,
                release=0.08, decay=4.5)
    return track


def rain_drops() -> list[float]:
    track = blank(4.3)
    events = ((0.0, 1175), (0.47, 880), (0.91, 1397), (1.48, 988),
              (1.96, 1568), (2.53, 1047), (3.02, 1319), (3.57, 880))
    for index, (start, note) in enumerate(events):
        add_osc(track, start, 0.32, note, 0.38,
                end_frequency=note * 0.72, attack=0.002,
                release=0.11, decay=4.8)
        add_noise_burst(track, start, 0.08, 0.16, 300 + index,
                        smooth=0.35, decay=8.0)
    return track


def wood_tap() -> list[float]:
    track = blank(4.0)
    notes = (330, 392, 294, 440, 392, 330, 494)
    for index, note in enumerate(notes):
        start = index * 0.52
        add_pluck(track, start, note, 0.42, 0.50, wooden=True)
        add_noise_burst(track, start, 0.07, 0.25, 510 + index,
                        smooth=0.82, decay=9.0)
    return track


def double_beat() -> list[float]:
    track = blank(4.4)
    for group in (0.0, 1.46, 2.92):
        for offset, level in ((0.0, 0.62), (0.23, 0.48)):
            start = group + offset
            add_osc(track, start, 0.24, 72, level,
                    end_frequency=48, attack=0.004, release=0.08, decay=5.0)
            # Small phone speakers cannot reproduce the 48–72 Hz body well.
            # Harmonic taps preserve the soft heartbeat character while
            # keeping the cue clearly audible on their limited drivers.
            add_osc(track, start, 0.18, 288, level * 0.38,
                    attack=0.002, release=0.06, decay=7.0)
            add_osc(track, start, 0.15, 576, level * 0.24,
                    attack=0.002, release=0.05, decay=8.0)
            add_osc(track, start, 0.12, 864, level * 0.14,
                    attack=0.002, release=0.04, decay=9.0)
            add_noise_burst(track, start, 0.12, level * 0.18,
                            int(start * 1000) + 811, smooth=0.9, decay=7.0)
    return track


def calm_chord() -> list[float]:
    track = blank(4.8)
    add_pad(track, 0.0, 2.45, (220.0, 277.2, 329.6), 0.20)
    add_pad(track, 2.2, 2.55, (196.0, 261.6, 329.6), 0.20)
    return track


SOUNDS = {
    "alert_urgent_pulse": urgent_pulse,
    "alert_air_horn": air_horn,
    "alert_rapid_beacon": rapid_beacon,
    "alert_rising_alarm": rising_alarm,
    "alert_double_knock": double_knock,
    "alert_crystal_bells": crystal_bells,
    "alert_door_chime": door_chime,
    "alert_temple_bowl": temple_bowl,
    "alert_glass_drops": glass_drops,
    "alert_music_box": music_box,
    "alert_wind_chimes": wind_chimes,
    "alert_sonar_ping": sonar_ping,
    "alert_radar_sweep": radar_sweep,
    "alert_pixel_jump": pixel_jump,
    "alert_retro_game": retro_game,
    "alert_signal_code": signal_code,
    "alert_neon_wave": neon_wave,
    "alert_bright_marimba": bright_marimba,
    "alert_piano_steps": piano_steps,
    "alert_sunrise": sunrise,
    "alert_major_arpeggio": major_arpeggio,
    "alert_minor_arpeggio": minor_arpeggio,
    "alert_soft_pop": soft_pop,
    "alert_rain_drops": rain_drops,
    "alert_wood_tap": wood_tap,
    "alert_double_beat": double_beat,
    "alert_calm_chord": calm_chord,
}


def encode(name: str, samples: list[float], ffmpeg: str) -> None:
    # A tiny fade on the whole rendered clip prevents decoder-edge clicks.
    fade = min(len(samples) // 2, int(0.018 * SAMPLE_RATE))
    for index in range(fade):
        scale = index / max(1, fade - 1)
        samples[index] *= scale
        samples[-1 - index] *= scale
    peak = max((abs(value) for value in samples), default=1.0)
    gain = 0.89 / max(peak, 1e-9)
    pcm = array("h", (int(max(-1.0, min(1.0, value * gain)) * 32767)
                      for value in samples)).tobytes()
    destination = OUTPUT / f"{name}.ogg"
    first_pass_filter = (
        f"loudnorm=I={TARGET_LOUDNESS_LUFS}:LRA=7:"
        f"TP={TARGET_TRUE_PEAK_DB}:print_format=json")
    first_pass = [ffmpeg, "-hide_banner", "-nostats",
                  "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1",
                  "-i", "pipe:0", "-af", first_pass_filter,
                  "-f", "null", "-"]
    measured = subprocess.run(first_pass, input=pcm, check=True,
                              capture_output=True).stderr.decode(
                                  "utf-8", errors="replace")
    json_start = measured.rfind("{")
    json_end = measured.rfind("}")
    if json_start < 0 or json_end <= json_start:
        raise RuntimeError(f"ffmpeg loudness analysis failed for {name}")
    loudness = json.loads(measured[json_start:json_end + 1])
    second_pass_filter = (
        f"loudnorm=I={TARGET_LOUDNESS_LUFS}:LRA=7:"
        f"TP={TARGET_TRUE_PEAK_DB}:"
        f"measured_I={loudness['input_i']}:"
        f"measured_LRA={loudness['input_lra']}:"
        f"measured_TP={loudness['input_tp']}:"
        f"measured_thresh={loudness['input_thresh']}:"
        f"offset={loudness['target_offset']}:linear=true"
    )
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
               "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1",
               "-i", "pipe:0", "-map_metadata", "-1", "-af",
               second_pass_filter, "-ar", str(SAMPLE_RATE), "-c:a",
               "libvorbis", "-q:a", "4", str(destination)]
    subprocess.run(command, input=pcm, check=True)


def main() -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise SystemExit("ffmpeg is required to encode the alert library")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, builder in SOUNDS.items():
        samples = builder()
        encode(name, samples, ffmpeg)
        print(f"generated {name}.ogg ({len(samples) / SAMPLE_RATE:.2f}s)")


if __name__ == "__main__":
    main()
