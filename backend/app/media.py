from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO

from fastapi import UploadFile
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


async def prepare_audio(upload: UploadFile, limit: int) -> PreparedAudio:
    media_type = (upload.content_type or "").split(";", 1)[0].strip().lower()
    audio_format = _AUDIO_FORMATS.get(media_type)
    if audio_format is None:
        raise MediaValidationError("audio format is not supported")
    raw = await _read_limited(upload, limit, "audio")
    return PreparedAudio(raw, audio_format)
