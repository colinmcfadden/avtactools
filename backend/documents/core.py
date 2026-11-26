"""What an upload becomes: a PDF, its page text and the JSON that describes them (docs/DOCUMENTS.md §2, §4, §8).

An upload comes from whoever sends it, so it is hostile input: its type is read from its content (the name only settles a legacy
Office file whose streams say nothing), its size is bounded before anything reads it, an Office file is converted by LibreOffice
in Gotenberg (never here), and the PDF is opened by pypdf with its pages counted before their text is read. Nothing in a PDF is run.

The original Office file is not kept (the owner, 2026-11-22): what is kept is exactly what is shown.
"""

from __future__ import annotations

import hashlib
import io
import json
import logging
import os
import re
import struct
import threading
import time
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone

from brief import gotenberg

# The limits of docs/DOCUMENTS.md §5.4, the owner's to change.
MAX_UPLOAD_BYTES = 25 * 1024 * 1024
MAX_PDF_BYTES = 50 * 1024 * 1024
MAX_PAGES = 500
MAX_TEXT_CHARS = 2_000_000
# Reading the text stops here and keeps what it has (``truncated``): a page can be slow to read, never a reason to refuse the PDF.
TEXT_SECONDS = 60
LIBRARY_MAX_DOCUMENTS = 200
LIBRARY_MAX_BYTES = 1024 * 1024 * 1024
PACK_MAX_DOCUMENTS = 100
PACK_MAX_BYTES = 1024 * 1024 * 1024

# What is taken: the PDF itself, and the Office files LibreOffice turns into one.
OFFICE_KINDS = ('doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx')
KINDS = ('pdf',) + OFFICE_KINDS

_PDF_MAGIC = b'%PDF-'
_ZIP_MAGIC = b'PK\x03\x04'
_OLE_MAGIC = bytes.fromhex('D0CF11E0A1B11AE1')
# An Office zip with more entries than this, or a directory of them larger than this, is not a document anyone wrote. Both are read
# from the zip's end record before ZipFile, which builds an object for every entry first: half a million empty entries fit in an
# upload and cost hundreds of MB (a real .pptx's directory is tens of KB).
_MAX_ZIP_ENTRIES = 20_000
_MAX_ZIP_DIRECTORY = 2 * 1024 * 1024
_EOCD = b'PK\x05\x06'
_ZIP64_LOCATOR = b'PK\x06\x07'
_ZIP64_EOCD = b'PK\x06\x06'
# Uploads read (and converted) at once. A PDF's streams inflate as they are read, a few hundred MB for a hostile one, in the one
# worker that also holds SAM (AGENTS.md §9): more wait their turn, and past READ_WAIT_S are told the server is busy.
READING_AT_ONCE = 2
READ_WAIT_S = 60
_reading = threading.BoundedSemaphore(READING_AT_ONCE)
# The name of a stream in a legacy (OLE) Office file, as its directory writes it: UTF-16, little-endian.
_OLE_STREAMS = (
    ('doc', 'WordDocument'),
    ('ppt', 'PowerPoint Document'),
    ('xls', 'Workbook'),
    ('xls', 'Book'),
)
# A password-protected .docx, .xlsx or .pptx is not a zip: it is an OLE file holding the encrypted package.
_OLE_ENCRYPTED = 'EncryptedPackage'.encode('utf-16-le')
_SPACE = re.compile(r'\s+')

logging.getLogger('pypdf').setLevel(logging.ERROR)


class DocumentError(ValueError):
    """An upload that cannot be kept, with the words to say, the API's ``code`` and the status to answer with."""

    def __init__(self, message, code, status):
        super().__init__(message)
        self.code = code
        self.status = status


def _refuse(message, code, status=422):
    raise DocumentError(message, code, status)


@dataclass(frozen=True)
class Prepared:
    """What is kept of one upload: the PDF, its page text as stored (None for a PDF with no text, a scan), and what describes them."""

    pdf: bytes
    text: bytes | None
    kind: str
    pages: int
    chars: int

    @property
    def pdf_sha256(self) -> str:
        return hashlib.sha256(self.pdf).hexdigest()

    @property
    def text_sha256(self) -> str | None:
        return hashlib.sha256(self.text).hexdigest() if self.text is not None else None


def kind_of(data: bytes, filename: str | None = None) -> str:
    """The upload's type, from its content: ``pdf``, ``docx``, ``xlsx``, ``pptx``, ``doc``, ``xls`` or ``ppt``."""
    if _PDF_MAGIC in data[:1024]:
        return 'pdf'                                   # PDF readers allow a little before the header, and so does this
    if data.startswith(_ZIP_MAGIC):
        return _ooxml_kind(data)
    if data.startswith(_OLE_MAGIC):
        return _ole_kind(data, filename)
    _refuse('EZ/PZ takes PDF, Word, Excel and PowerPoint files.', 'unsupported_type', 415)


def _zip_directory(data):
    """``(entries, bytes)`` of a zip's central directory as its end record says, or None where there is no end record to read."""
    at = data.rfind(_EOCD, max(0, len(data) - 22 - 65535))
    if at < 0 or len(data) < at + 22:
        return None
    entries, size = struct.unpack('<HI', data[at + 10:at + 16])
    if entries == 0xFFFF or size == 0xFFFFFFFF:
        # Zip64: the counts are in its own end record, which the locator just before this one names.
        locator = at - 20
        if locator < 0 or data[locator:locator + 4] != _ZIP64_LOCATOR:
            return None
        (offset,) = struct.unpack('<Q', data[locator + 8:locator + 16])
        if offset + 56 > len(data) or data[offset:offset + 4] != _ZIP64_EOCD:
            return None
        entries, size = struct.unpack('<QQ', data[offset + 32:offset + 48])
    return entries, size


def _ooxml_kind(data):
    directory = _zip_directory(data)
    if directory is None or directory[0] > _MAX_ZIP_ENTRIES or directory[1] > _MAX_ZIP_DIRECTORY:
        _refuse('That file could not be read.', 'unreadable')
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            if len(infos) > _MAX_ZIP_ENTRIES:
                _refuse('That file could not be read.', 'unreadable')
            names = {info.filename for info in infos}
    except zipfile.BadZipFile:
        _refuse('That file could not be read.', 'unreadable')
    # Only the parts are looked at, never inflated: LibreOffice reads the file, in Gotenberg.
    if '[Content_Types].xml' in names:
        if 'word/document.xml' in names:
            return 'docx'
        if 'xl/workbook.xml' in names:
            return 'xlsx'
        if 'ppt/presentation.xml' in names:
            return 'pptx'
    _refuse('EZ/PZ takes PDF, Word, Excel and PowerPoint files.', 'unsupported_type', 415)


def _ole_kind(data, filename):
    if _OLE_ENCRYPTED in data:
        _refuse('That file has a password: save it without one and upload it again.', 'password_protected')
    for kind, stream in _OLE_STREAMS:
        if stream.encode('utf-16-le') in data:
            return kind
    extension = os.path.splitext(filename or '')[1].lower().lstrip('.')
    if extension in ('doc', 'xls', 'ppt'):
        return extension                               # a stream named in a sector this did not find; LibreOffice reads the rest
    _refuse('EZ/PZ takes PDF, Word, Excel and PowerPoint files.', 'unsupported_type', 415)


def _convert(data, kind):
    try:
        return gotenberg.convert_office(data, kind, max_bytes=MAX_PDF_BYTES)
    except gotenberg.Unavailable:
        _refuse('This server cannot turn Office files into PDFs. Save it as a PDF and upload that.', 'conversion_unavailable', 503)
    except gotenberg.Busy:
        _refuse('The converter is busy: try again in a minute.', 'converter_busy', 503)
    except gotenberg.TooLarge:
        _refuse('The PDF made from that file is larger than 50 MB.', 'pdf_too_large', 413)
    except gotenberg.Refused:
        # Gotenberg's 400: the file needs a password, or LibreOffice cannot read it. Its words name neither for certain.
        _refuse('That file could not be turned into a PDF. If it has a password, save it without one and upload it again.',
                'conversion_refused')
    except gotenberg.Failed:
        _refuse('The converter did not answer: try again.', 'conversion_failed', 502)


def _fold(text):
    return _SPACE.sub(' ', text.replace('\x00', ' ')).strip()


def read_pdf(data: bytes):
    """``(pages, texts, truncated)`` of a PDF that can be kept, or DocumentError. ``texts`` is each page's text with every run of
    white space one space; pages past the text's limit or its time are empty, and ``truncated`` says so."""
    if len(data) > MAX_PDF_BYTES:
        _refuse('The PDF is larger than 50 MB.', 'pdf_too_large', 413)
    if _PDF_MAGIC not in data[:1024]:
        _refuse('That file could not be read as a PDF.', 'unreadable')
    from pypdf import PdfReader                        # imported here so the rest of the API starts without it
    try:
        reader = PdfReader(io.BytesIO(data), strict=False)
        if reader.is_encrypted:
            # A PDF that only limits printing or copying opens with no password; one that needs a password cannot be shown.
            try:
                opened = reader.decrypt('')
            except Exception:  # noqa: BLE001 - pypdf raises what its cipher raises, or DependencyError for one it cannot do
                opened = 0
            if not opened:
                _refuse('That PDF has a password: save it without one and upload it again.', 'password_protected')
        pages = list(reader.pages)
    except DocumentError:
        raise
    except Exception:  # noqa: BLE001 - a damaged file can end in any of pypdf's errors, or Python's
        _refuse('That file could not be read as a PDF.', 'unreadable')
    if not pages:
        _refuse('That PDF has no pages.', 'no_pages')
    if len(pages) > MAX_PAGES:
        _refuse(f'A document can have at most {MAX_PAGES} pages; this one has {len(pages)}.', 'too_many_pages', 413)

    texts, chars, truncated = [], 0, False
    started = time.monotonic()
    for page in pages:
        if truncated or time.monotonic() - started > TEXT_SECONDS:
            truncated = True
            texts.append('')
            continue
        try:
            text = _fold(page.extract_text() or '')
        except Exception:  # noqa: BLE001 - a page whose text cannot be read is a page with no text, not a PDF refused
            text = ''
        if chars + len(text) > MAX_TEXT_CHARS:
            text = text[:MAX_TEXT_CHARS - chars]
            truncated = True
        chars += len(text)
        texts.append(text)
    return len(pages), texts, truncated


def text_file(texts, truncated):
    """The page text as it is stored and sent, or None when no page has any (a scan): there is nothing to search."""
    if not any(texts):
        return None
    body = {'version': 1, 'pages': texts, 'truncated': truncated}
    return json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def prepare(data: bytes, filename: str | None = None) -> Prepared:
    """What is kept of an upload: its PDF (an Office file's made by LibreOffice) and its page text. DocumentError when it cannot be."""
    if not data:
        _refuse('That file is empty.', 'empty_file', 400)
    if len(data) > MAX_UPLOAD_BYTES:
        _refuse('A document can be at most 25 MB.', 'file_too_large', 413)
    if not _reading.acquire(timeout=READ_WAIT_S):
        _refuse('The server is busy reading other documents: try again in a minute.', 'converter_busy', 503)
    try:
        kind = kind_of(data, filename)
        pdf = data if kind == 'pdf' else _convert(data, kind)
        pages, texts, truncated = read_pdf(pdf)
        text = text_file(texts, truncated)
    finally:
        _reading.release()
    return Prepared(pdf=pdf, text=text, kind=kind, pages=pages, chars=sum(len(t) for t in texts))


def default_name(filename: str | None) -> str:
    """The title offered for a file: its name less its extension, at most 100 characters, else DOCUMENT."""
    stem = os.path.splitext(os.path.basename((filename or '').replace('\\', '/')))[0].strip()
    stem = ''.join(ch for ch in stem if ch.isprintable())
    return stem[:100].strip() or 'DOCUMENT'


def describe(prepared: Prepared, filename: str | None, source_bytes: int, now: datetime | None = None) -> dict:
    """The JSON a Library record and a pack item hold (docs/DOCUMENTS.md §2): small, naming the files kept by their hashes."""
    now = now or datetime.now(timezone.utc)
    name = os.path.basename((filename or '').replace('\\', '/'))
    name = ''.join(ch for ch in name if ch.isprintable())[:255]
    return {
        'version': 1,
        'pdf': {'sha256': prepared.pdf_sha256, 'bytes': len(prepared.pdf), 'pages': prepared.pages},
        'text': {'sha256': prepared.text_sha256, 'chars': prepared.chars} if prepared.text is not None else None,
        'source': {'type': prepared.kind, 'name': name or None, 'bytes': source_bytes},
        'added_at': now.astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    }


_HEX64 = re.compile(r'^[0-9a-f]{64}\Z')


def hashes_of(data) -> set[str]:
    """The files a document's JSON names: its PDF's and its text's hashes. Nothing for anything that is not one."""
    found = set()
    if not isinstance(data, dict):
        return found
    for part in ('pdf', 'text'):
        value = data.get(part)
        digest = value.get('sha256') if isinstance(value, dict) else None
        if isinstance(digest, str) and _HEX64.match(digest):
            found.add(digest)
    return found


def pdf_bytes_of(data) -> int:
    pdf = data.get('pdf') if isinstance(data, dict) else None
    size = pdf.get('bytes') if isinstance(pdf, dict) else None
    return size if isinstance(size, int) and not isinstance(size, bool) and size > 0 else 0
