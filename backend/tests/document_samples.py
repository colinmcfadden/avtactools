"""Files to upload in the documents' tests: real PDFs built here (text pages, blank pages, encrypted), and the shapes of Office files.

Built rather than committed, so a test says what is in its file. A PDF's pages hold one line of Helvetica each, which pypdf reads back.
"""

import io
import zipfile


def _escape(text):
    return text.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')


def pdf_with(*pages):
    """A PDF of one page per argument, each the text given (an empty one is a blank page, as a scan reads to pypdf)."""
    objects = []                                             # each object's body, numbered from 1

    def add(body):
        objects.append(body)
        return len(objects)

    catalog = add(None)
    page_tree = add(None)
    font = add(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    kids = []
    for text in pages:
        stream = f'BT /F1 12 Tf 72 720 Td ({_escape(text)}) Tj ET'.encode('latin-1') if text else b''
        contents = add(b'<< /Length %d >>\nstream\n' % len(stream) + stream + b'\nendstream')
        kids.append(add(f'<< /Type /Page /Parent {page_tree} 0 R /MediaBox [0 0 612 792] /Contents {contents} 0 R '
                        f'/Resources << /Font << /F1 {font} 0 R >> >> >>'.encode()))
    objects[catalog - 1] = f'<< /Type /Catalog /Pages {page_tree} 0 R >>'.encode()
    objects[page_tree - 1] = f'<< /Type /Pages /Kids [{" ".join(f"{k} 0 R" for k in kids)}] /Count {len(kids)} >>'.encode()

    out = io.BytesIO()
    out.write(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(b'%d 0 obj\n' % number + body + b'\nendobj\n')
    xref = out.tell()
    out.write(b'xref\n0 %d\n0000000000 65535 f \n' % (len(objects) + 1))
    for offset in offsets:
        out.write(b'%010d 00000 n \n' % offset)
    out.write(b'trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n' % (len(objects) + 1, catalog, xref))
    return out.getvalue()


def encrypted(pdf, user_password, owner_password='owner'):
    """The PDF encrypted: with a user password it cannot be opened without one; with an empty one it only restricts."""
    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter()
    for page in PdfReader(io.BytesIO(pdf)).pages:
        writer.add_page(page)
    writer.encrypt(user_password=user_password, owner_password=owner_password, algorithm='RC4-128')
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()


def ooxml(kind):
    """A zip shaped as a .docx, .xlsx or .pptx: the parts the server reads its type from. LibreOffice is stood in for in the tests."""
    main = {'docx': 'word/document.xml', 'xlsx': 'xl/workbook.xml', 'pptx': 'ppt/presentation.xml'}[kind]
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as archive:
        archive.writestr('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        archive.writestr(main, '<root/>')
    return out.getvalue()


def ole(*streams):
    """The start of a legacy Office (OLE) file naming the streams given, as its directory writes them (UTF-16, little-endian)."""
    return bytes.fromhex('D0CF11E0A1B11AE1') + b'\x00' * 504 + b''.join(s.encode('utf-16-le') + b'\x00\x00' for s in streams)


class FakeResponse:
    """An answer of Gotenberg's, read whole (``content``) or streamed (``iter_content``, as a conversion reads it)."""

    def __init__(self, status_code, content=b'', text=''):
        self.status_code = status_code
        self.content = content
        self.text = text

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def iter_content(self, chunk_size=1):
        for start in range(0, len(self.content), chunk_size):
            yield self.content[start:start + chunk_size]
