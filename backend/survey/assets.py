"""Where a survey's photos, 3D captures and (later) printed pages are kept (docs/LZ_SURVEYS.md §3.2).

One small interface, ``AssetStore`` (put, get, delete, by key), so moving from the VM's disk to an S3 bucket is a new class
and a copy, not a change to the routes. A key is the SHA-256 of the bytes stored, so a file is written once and never
changed in place; two surveys with the same photo share it.

Photos come from whoever uploads them, so they are read as hostile input: only JPEG and PNG, at most ``MAX_UPLOAD_BYTES``,
decoded by Pillow, turned the way their orientation says, shrunk to ``MAX_EDGE_PX`` on the long side and written again with
**no metadata at all** (EXIF, with any GPS position and camera serial in it, XMP, ICC, PNG text). A 3D capture is the app's
own PNG and is stored as sent, once it has been checked to be one.
"""

from __future__ import annotations

import hashlib
import io
import os
import re
import tempfile
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError

MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_EDGE_PX = 2400
# Past this many pixels a picture is refused before it is decoded: a small file can inflate to gigabytes.
MAX_PIXELS = 80_000_000
JPEG_QUALITY = 88

_KEY = re.compile(r"^[0-9a-f]{64}$")


class AssetError(ValueError):
    """An upload that cannot be stored, with a ``code`` for the API's answer."""

    def __init__(self, message, code):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class Prepared:
    data: bytes
    content_type: str
    width: int
    height: int

    @property
    def key(self) -> str:
        return hashlib.sha256(self.data).hexdigest()


class AssetStore:
    """Bytes by key. A key is a SHA-256 in lowercase hex; anything else is refused before it reaches storage."""

    def put(self, key: str, data: bytes) -> None:
        raise NotImplementedError

    def get(self, key: str) -> bytes | None:
        raise NotImplementedError

    def delete(self, key: str) -> None:
        raise NotImplementedError

    def exists(self, key: str) -> bool:
        return self.get(key) is not None

    def has(self, key: str) -> bool:
        """Whether the bytes are there, without reading them (a book's PDF is asked about every few seconds while it prints)."""
        return self.exists(key)

    def path(self, key: str) -> str | None:
        """Where the bytes are on this machine's disk, to be sent from there rather than read whole into memory; None for a store
        that keeps them elsewhere, or when they are not there."""
        return None


def check_key(key) -> str:
    if not isinstance(key, str) or not _KEY.match(key):
        raise ValueError("An asset key is a SHA-256 in lowercase hex")
    return key


class LocalAssetStore(AssetStore):
    """Files under ``root``, two levels of folders from the key (``ab/abcdef…``), each written whole and renamed into place."""

    def __init__(self, root: str):
        self.root = os.path.abspath(root)

    def _path(self, key: str) -> str:
        key = check_key(key)
        return os.path.join(self.root, key[:2], key)

    def put(self, key: str, data: bytes) -> None:
        path = self._path(key)
        if os.path.exists(path):
            return  # the same bytes: a key names its content
        os.makedirs(os.path.dirname(path), exist_ok=True)
        fd, temporary = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".incoming-")
        try:
            with os.fdopen(fd, "wb") as out:
                out.write(data)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, path)
        except BaseException:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise

    def get(self, key: str) -> bytes | None:
        try:
            with open(self._path(key), "rb") as source:
                return source.read()
        except FileNotFoundError:
            return None

    def delete(self, key: str) -> None:
        try:
            os.unlink(self._path(key))
        except FileNotFoundError:
            pass

    def has(self, key: str) -> bool:
        return os.path.isfile(self._path(key))

    def path(self, key: str) -> str | None:
        path = self._path(key)
        return path if os.path.isfile(path) else None


def default_root() -> str:
    """``SURVEY_ASSETS_DIR``, else a folder in the system's temp directory (a bare local run: lost on reboot, so a deployment sets it)."""
    return os.environ.get("SURVEY_ASSETS_DIR") or os.path.join(tempfile.gettempdir(), "ezpz-survey-assets")


def store() -> AssetStore:
    """The store the routes use. Read afresh each time, so a test (or a change of setting) is seen without a restart."""
    return LocalAssetStore(default_root())


def _open(data: bytes) -> Image.Image:
    try:
        image = Image.open(io.BytesIO(data))
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as error:
        raise AssetError("That file is not a picture that can be read.", "not_an_image") from error
    if image.format not in ("JPEG", "PNG"):
        raise AssetError("Send a JPEG or a PNG.", "unsupported_image")
    width, height = image.size
    if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
        raise AssetError("That picture is too large to read.", "image_too_large")
    return image


def prepare_photo(data: bytes) -> Prepared:
    """A photo as it is kept: the right way up, at most MAX_EDGE_PX on its long side, with no metadata. JPEG stays JPEG, PNG PNG."""
    if len(data) > MAX_UPLOAD_BYTES:
        raise AssetError("A photo can be at most 20 MB.", "too_large")
    image = _open(data)
    source_format = image.format
    try:
        image.load()
        image = ImageOps.exif_transpose(image)  # turned as its orientation says, before the orientation goes with the rest
    except (OSError, ValueError, Image.DecompressionBombError) as error:
        raise AssetError("That picture could not be read whole.", "not_an_image") from error
    image.thumbnail((MAX_EDGE_PX, MAX_EDGE_PX), Image.Resampling.LANCZOS)

    out = io.BytesIO()
    if source_format == "JPEG":
        image = image.convert("RGB")
        clean = Image.new("RGB", image.size)
        clean.paste(image)
        clean.save(out, "JPEG", quality=JPEG_QUALITY, optimize=True)
        content_type = "image/jpeg"
    else:
        mode = "RGBA" if image.mode in ("RGBA", "LA", "P") else "RGB"
        image = image.convert(mode)
        clean = Image.new(mode, image.size)
        clean.paste(image)
        clean.save(out, "PNG", optimize=True)
        content_type = "image/png"
    # A new image holds pixels only: nothing of the file's info (EXIF, XMP, ICC, text chunks) is carried into it.
    return Prepared(out.getvalue(), content_type, clean.width, clean.height)


def prepare_capture(data: bytes) -> Prepared:
    """A 3D capture, stored as sent once it is known to be a PNG Pillow can read."""
    if len(data) > MAX_UPLOAD_BYTES:
        raise AssetError("A capture can be at most 20 MB.", "too_large")
    image = _open(data)
    if image.format != "PNG":
        raise AssetError("A 3D capture is a PNG.", "unsupported_image")
    try:
        image.verify()
    except Exception as error:  # Pillow's verify raises whatever the damaged chunk caused
        raise AssetError("That picture could not be read whole.", "not_an_image") from error
    return Prepared(data, "image/png", image.width, image.height)
