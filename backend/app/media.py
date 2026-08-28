from __future__ import annotations

import math
from dataclasses import dataclass
from io import BytesIO

from fastapi import UploadFile
from mutagen import File as MutagenFile
from mutagen.aac import AAC
from mutagen.flac import FLAC
from mutagen.mp3 import MP3
from mutagen.mp4 import MP4
from mutagen.oggflac import OggFLAC
from mutagen.oggopus import OggOpus
from mutagen.oggspeex import OggSpeex
from mutagen.oggvorbis import OggVorbis
from mutagen.wave import WAVE
from PIL import Image, ImageOps, UnidentifiedImageError


Image.MAX_IMAGE_PIXELS = 24_000_000


class MediaValidationError(ValueError):
    def __init__(self, detail: str, status_code: int = 415):
        super().__init__(detail)
        self.detail = detail
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class PreparedImage:
    data: bytes
    media_type: str = "image/jpeg"
    source_bytes: int = 0


@dataclass(frozen=True, slots=True)
class PreparedAudio:
    data: bytes
    format: str
    duration_seconds: float = 0.0


async def _read_limited(upload: UploadFile, limit: int, label: str) -> bytes:
    data = await upload.read(limit + 1)
    await upload.close()
    if not data:
        raise MediaValidationError(f"{label} is empty", 400)
    if len(data) > limit:
        raise MediaValidationError(
            f"{label} exceeds the configured size limit of {limit} bytes", 413
        )
    return data


async def prepare_image(upload: UploadFile, limit: int) -> PreparedImage:
    raw = await _read_limited(upload, limit, "photo")
    try:
        with Image.open(BytesIO(raw)) as source:
            if source.width <= 0 or source.height <= 0:
                raise MediaValidationError("photo has invalid dimensions")
            if source.width * source.height > Image.MAX_IMAGE_PIXELS:
                raise MediaValidationError("photo has too many pixels", 413)
            source.load()
            image = ImageOps.exif_transpose(source)
            image.thumbnail((2400, 2400), Image.Resampling.LANCZOS)
            if image.mode not in ("RGB", "L"):
                background = Image.new("RGB", image.size, "white")
                if "A" in image.getbands():
                    background.paste(image, mask=image.getchannel("A"))
                else:
                    background.paste(image.convert("RGB"))
                image = background
            elif image.mode == "L":
                image = image.convert("RGB")
            output = BytesIO()
            # Re-encoding removes EXIF/GPS metadata before any external request.
            image.save(output, format="JPEG", quality=90, optimize=True)
            return PreparedImage(output.getvalue(), source_bytes=len(raw))
    except MediaValidationError:
        raise
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as error:
        raise MediaValidationError("photo is not a supported valid image") from error


_AUDIO_FORMATS = {
    "audio/aac": "aac",
    "audio/flac": "flac",
    "audio/m4a": "m4a",
    "audio/mp4": "m4a",
    "audio/mpeg": "mp3",
    "audio/ogg": "ogg",
    "audio/wav": "wav",
    "audio/x-m4a": "m4a",
    "audio/x-wav": "wav",
}

_AUDIO_CONTAINER_TYPES = {
    "aac": (AAC,),
    "flac": (FLAC,),
    "m4a": (MP4,),
    "mp3": (MP3,),
    "ogg": (OggVorbis, OggOpus, OggSpeex, OggFLAC),
    "wav": (WAVE,),
}

# Voice intake is designed for short utterances. Enforcing the duration from the
# decoded container metadata prevents a small, highly compressed multi-hour file
# from bypassing the byte-size limit and being sent to the transcription model.
MAX_AUDIO_DURATION_SECONDS = 90.0

# Mutagen normally reads these fields from an MP4 audio sample entry. Some
# Android MediaRecorder/MPEG-4 files (including Samsung recordings) expose a
# valid duration through Mutagen but leave MP4Info.sample_rate and .channels at
# zero. Keep the fallback deliberately small and structural: it only walks the
# standard audio-track atom path and never searches for a loose ``mp4a`` byte
# sequence.
_MAX_MP4_ATOMS = 4_096
_MAX_MP4_TRACKS = 64
_MAX_MP4_SAMPLE_DESCRIPTIONS = 64


class _InvalidMp4Atoms(ValueError):
    pass


def _mp4_atoms(
    raw: bytes,
    start: int,
    end: int,
    atom_budget: list[int],
) -> list[tuple[bytes, int, int]]:
    """Return (type, payload_start, atom_end) for one bounded atom level."""

    if start < 0 or end < start or end > len(raw):
        raise _InvalidMp4Atoms

    atoms: list[tuple[bytes, int, int]] = []
    cursor = start
    while cursor < end:
        if end - cursor < 8:
            raise _InvalidMp4Atoms

        size = int.from_bytes(raw[cursor : cursor + 4], "big")
        atom_type = raw[cursor + 4 : cursor + 8]
        header_size = 8
        if size == 1:
            if end - cursor < 16:
                raise _InvalidMp4Atoms
            size = int.from_bytes(raw[cursor + 8 : cursor + 16], "big")
            header_size = 16
        elif size == 0:
            size = end - cursor

        if size < header_size or size > end - cursor:
            raise _InvalidMp4Atoms

        atom_budget[0] += 1
        if atom_budget[0] > _MAX_MP4_ATOMS:
            raise _InvalidMp4Atoms

        atom_end = cursor + size
        atoms.append((atom_type, cursor + header_size, atom_end))
        cursor = atom_end

    return atoms


def _mp4a_stream_metadata(raw: bytes) -> tuple[int, int] | None:
    """Read (sample_rate, channels) from a standard MP4 audio sample entry."""

    try:
        budget = [0]
        top_level = _mp4_atoms(raw, 0, len(raw), budget)
        tracks_seen = 0
        candidates: set[tuple[int, int]] = set()

        for atom_type, moov_start, moov_end in top_level:
            if atom_type != b"moov":
                continue
            for child_type, trak_start, trak_end in _mp4_atoms(
                raw, moov_start, moov_end, budget
            ):
                if child_type != b"trak":
                    continue
                tracks_seen += 1
                if tracks_seen > _MAX_MP4_TRACKS:
                    raise _InvalidMp4Atoms

                for trak_type, mdia_start, mdia_end in _mp4_atoms(
                    raw, trak_start, trak_end, budget
                ):
                    if trak_type != b"mdia":
                        continue
                    mdia_atoms = _mp4_atoms(raw, mdia_start, mdia_end, budget)
                    is_audio_track = any(
                        nested_type == b"hdlr"
                        and nested_end - nested_start >= 12
                        and raw[nested_start + 8 : nested_start + 12] == b"soun"
                        for nested_type, nested_start, nested_end in mdia_atoms
                    )
                    if not is_audio_track:
                        continue

                    for mdia_type, minf_start, minf_end in mdia_atoms:
                        if mdia_type != b"minf":
                            continue
                        for minf_type, stbl_start, stbl_end in _mp4_atoms(
                            raw, minf_start, minf_end, budget
                        ):
                            if minf_type != b"stbl":
                                continue
                            for stbl_type, stsd_start, stsd_end in _mp4_atoms(
                                raw, stbl_start, stbl_end, budget
                            ):
                                if stbl_type != b"stsd" or stsd_end - stsd_start < 8:
                                    continue

                                # SampleDescriptionBox is a version-0 FullBox.
                                if raw[stsd_start] != 0:
                                    raise _InvalidMp4Atoms

                                entry_count = int.from_bytes(
                                    raw[stsd_start + 4 : stsd_start + 8], "big"
                                )
                                if entry_count > _MAX_MP4_SAMPLE_DESCRIPTIONS:
                                    raise _InvalidMp4Atoms
                                entries = _mp4_atoms(
                                    raw, stsd_start + 8, stsd_end, budget
                                )
                                if len(entries) != entry_count:
                                    raise _InvalidMp4Atoms

                                for entry_type, entry_start, entry_end in entries:
                                    if entry_type != b"mp4a":
                                        continue
                                    # ISO/IEC 14496-12 AudioSampleEntry fields:
                                    # reserved/data ref (8), version/revision/vendor
                                    # (8), channels (2), sample size (2), reserved
                                    # (4), and 16.16 sample rate (4).
                                    if entry_end - entry_start < 28:
                                        raise _InvalidMp4Atoms
                                    data_reference_index = int.from_bytes(
                                        raw[entry_start + 6 : entry_start + 8], "big"
                                    )
                                    version = int.from_bytes(
                                        raw[entry_start + 8 : entry_start + 10], "big"
                                    )
                                    channels = int.from_bytes(
                                        raw[entry_start + 16 : entry_start + 18], "big"
                                    )
                                    sample_size = int.from_bytes(
                                        raw[entry_start + 18 : entry_start + 20], "big"
                                    )
                                    fixed_sample_rate = int.from_bytes(
                                        raw[entry_start + 24 : entry_start + 28], "big"
                                    )

                                    # Version 1 retains the base fields and has
                                    # a mandatory 16-byte extension. Version 2
                                    # uses a different layout and is not guessed.
                                    if version not in (0, 1) or (
                                        version == 1 and entry_end - entry_start < 44
                                    ):
                                        raise _InvalidMp4Atoms
                                    if (
                                        data_reference_index == 0
                                        or channels < 1
                                        or channels > 8
                                        or sample_size not in (8, 16, 24, 32)
                                        or fixed_sample_rate & 0xFFFF
                                    ):
                                        raise _InvalidMp4Atoms
                                    sample_rate = fixed_sample_rate >> 16
                                    if sample_rate < 4_000 or sample_rate > 65_535:
                                        raise _InvalidMp4Atoms
                                    candidates.add((sample_rate, channels))

        # Multiple conflicting descriptions are ambiguous, so fail closed.
        if len(candidates) != 1:
            return None
        return next(iter(candidates))
    except _InvalidMp4Atoms:
        return None


def _validated_audio_duration(raw: bytes, expected_format: str) -> float:
    try:
        parsed = MutagenFile(BytesIO(raw))
        # Raw ADTS/ADIF AAC has no generic Mutagen score without a filename,
        # while UploadFile bytes deliberately have no trusted extension here.
        if parsed is None:
            parsed = AAC(BytesIO(raw))
    except Exception as error:
        raise MediaValidationError(
            "audio is not a supported valid audio container"
        ) from error

    if parsed is None:
        raise MediaValidationError("audio is not a supported valid audio container")
    if not isinstance(parsed, _AUDIO_CONTAINER_TYPES[expected_format]):
        raise MediaValidationError(
            "audio content does not match its declared media type"
        )

    info = getattr(parsed, "info", None)
    try:
        duration = float(info.length)
        # Opus always decodes at 48 kHz; Mutagen intentionally omits a
        # sample_rate attribute for OggOpusInfo.
        sample_rate = int(
            getattr(
                info,
                "sample_rate",
                48_000 if isinstance(parsed, OggOpus) else 0,
            )
        )
        channels = int(info.channels)
    except (AttributeError, TypeError, ValueError, OverflowError) as error:
        raise MediaValidationError("audio has invalid stream metadata") from error

    if isinstance(parsed, MP4) and expected_format == "m4a" and (
        sample_rate == 0 or channels == 0
    ):
        fallback_metadata = _mp4a_stream_metadata(raw)
        if fallback_metadata is not None:
            fallback_sample_rate, fallback_channels = fallback_metadata
            # If Mutagen did provide either field, require the structural parser
            # to agree instead of silently overriding contradictory metadata.
            if sample_rate not in (0, fallback_sample_rate) or channels not in (
                0,
                fallback_channels,
            ):
                raise MediaValidationError("audio has invalid stream metadata")
            sample_rate = fallback_sample_rate
            channels = fallback_channels

    if (
        not math.isfinite(duration)
        or duration <= 0.0
        or sample_rate <= 0
        or channels <= 0
    ):
        raise MediaValidationError("audio has invalid stream metadata")
    if duration > MAX_AUDIO_DURATION_SECONDS:
        raise MediaValidationError(
            f"audio duration exceeds the {MAX_AUDIO_DURATION_SECONDS:g} second limit",
            413,
        )
    return duration


async def prepare_audio(upload: UploadFile, limit: int) -> PreparedAudio:
    media_type = (upload.content_type or "").split(";", 1)[0].strip().lower()
    audio_format = _AUDIO_FORMATS.get(media_type)
    if audio_format is None:
        raise MediaValidationError("audio format is not supported")
    raw = await _read_limited(upload, limit, "audio")
    duration = _validated_audio_duration(raw, audio_format)
    return PreparedAudio(raw, audio_format, duration)
