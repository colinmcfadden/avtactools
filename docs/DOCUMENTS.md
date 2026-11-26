# Documents — plan

Status: **decided** (2026-11-22; the owner's answers are §13). D1, the server, is built; D2, the web, is next. The owner asked for a place to keep the OPORD and the other papers of a mission
beside its plan, to look through quickly while planning: a document is uploaded to a person's **Library** or into a **Mission Pack**,
where it sits in the Pack panel's **Items** beside the LZ/PZs, route sets and point sets. Word, Excel, PowerPoint and PDF are taken, and
every one is **shown as a PDF**, on the web, Android and later iOS. It is **not an editor**: a document is read, never changed, with a
basic search. **§13 holds the owner's answers** (2026-11-22): nothing more is asked or checked at upload than today (§3), only the PDF is
kept, the web shows it with pdf.js, and the server reads each page's text with pypdf for search. Where this plan and the code disagree once it is built, the code wins (AGENTS.md §16).

---

## 1. What it is

A document is **a PDF and the words on its pages**, kept by the server and read on every device:

- **Upload** a PDF, or a Word, Excel or PowerPoint file, which the server turns into a PDF (§4). What is kept is the PDF (§4.3).
- **Read** it: pages, zoom, *page n of N*, and **find in the document** (§8). Links in it are not followed and nothing in it runs.
- **Find it** by name in the Library and in a pack, as every item is found.
- **Keep it** in the Library (synced to every device of the person's, §6) or in a pack (shared with everyone in it, §7), and copy it
  between them as LZ/PZs are copied. A newer version of the paper replaces the old one under the same name (§5.4).
- **Share or save the PDF** out of the app, as the brief's PDF is (the share sheet, *Save to device*, a download on the web).

What it is not, on purpose: an editor, a converter people download Office files from, a place for imagery or overlays (those are the
Library's *Overlays*), or a store for anything but unclassified papers (§3).

---

## 2. The shape of it

```
 Upload (web, Android, iOS)                          Read (every client)
   │ PDF / DOCX / XLSX / PPTX (+ legacy .doc/.xls/.ppt)  ▲  the PDF, by the record or item it belongs to
   ▼                                                   │  the page text, for find in the document
 POST /api/documents  or  POST /api/packs/<uuid>/documents
   ├─ type read from the content, never the name alone
   ├─ Office → Gotenberg's LibreOffice route → PDF      (§4)
   ├─ the PDF checked: opens, no password, ≤ 500 pages (pypdf, §8.3)
   ├─ its text read page by page                         (§8)
   ├─ PDF and text written to the asset store by SHA-256 (§5)
   └─ a SavedDocument (Library) or a `document` pack item, whose JSON names the two files
```

The JSON a Library record and a pack item both hold is small; the bytes never go in the database:

```json
{
  "version": 1,
  "pdf":    { "sha256": "<64 hex>", "bytes": 913442, "pages": 14 },
  "text":   { "sha256": "<64 hex>", "chars": 48210 },
  "source": { "type": "docx", "name": "OPORD 24-03.docx", "bytes": 482113 },
  "added_at": "2026-11-22T15:04:05Z"
}
```

`text` is null for a PDF with no text in it (a scan). `source` says what was uploaded, so a row can say *from DOCX*; it names no stored
file: the original is not kept (§4.3). The record's or item's `name` is the document's title: the file's name
less its extension, as offered at upload, and changed by rename as any item's is.

---

## 3. Classification

AGENTS.md §2: **unclassified only, no CUI.** An OPORD, its annexes and a sync matrix are often marked CUI, and nothing hosting EZ/PZ
(Vercel, a home server, Supabase) is an authorised boundary, so a CUI document uploaded would be a spill. How far the app should go at
upload was the owner's call, from nothing, through a notice or a box ticked at every upload, to the server refusing a file whose own pages
are marked CUI or classified.

**Decided (the owner, 2026-11-22): nothing.** The upload asks for no acknowledgement and reads no markings; the rule stands as it does for
every other thing a person puts in EZ/PZ, and `docs/USER_GUIDE.md`'s section on documents says it. Should that change, the server already
reads each page's text (§8.2), so a check of banner lines (`CUI`, `FOUO`, `SECRET//…` alone on a page's first or last lines, or a CUI
designation block on the first page) is a small addition to the upload, with no change to any client but the words of its refusal.

### 3.1 Deleting one

A document deleted from a Library, or removed from a pack, is gone from there at once (a tombstone, its content blanked), and its files are
deleted once nothing else names them (§5.3): a copy someone made into a pack, or from a pack into their own Library, is theirs and keeps
them. Devices drop their kept copy when the deletion reaches them (§9.2). **Not in v1:** removing every copy everywhere at once (an
administrator's *purge*, for a document that should never have been uploaded), and the record of who uploaded which file that it would
work from. Both are a later step if the owner wants them; the store keeps files by hash, which makes finding every copy straightforward.

---

## 4. Conversion

### 4.1 Office files through Gotenberg's LibreOffice route

Gotenberg already prints the brief and the LZ survey pages (`backend/brief/gotenberg.py`, `GOTENBERG_URL`, AGENTS.md §9), and the same
image carries LibreOffice: `POST /forms/libreoffice/convert`, one file in, one PDF out. No new service. `gotenberg.convert_office(data,
kind)` beside `print_pdf`, sending:

- `singlePageSheets=true` for a spreadsheet, so a sync matrix is one page per sheet to zoom into, not forty pages cut at the print area;
- `exportNotes=false`, `exportHiddenSlides=false` (the defaults, sent anyway): speaker notes, comments and hidden slides are not in the PDF;
- `updateIndexes=true` (the default): a table of contents is brought up to date;
- no `password`: a file that needs one is answered 400 by Gotenberg, and refused here as *That file has a password: save it without one
  and upload it again.*

**What LibreOffice does with a hostile file** (Gotenberg 8.x's own documentation): macros never run (`.docm`, `.xlsm`, `.pptm` convert
without them); **from 8.34.0** linked content (a `file://` path, an external image or object) is dropped, never fetched; any fetch it still
makes goes through the filtering proxy, which **`--libreoffice-deny-private-ips=true`** keeps off the VM's own network. So the Gotenberg
the brief waits on is deployed at **one exact 8.x version, 8.34.0 or later** (the brief was tried with 8.21.1, and is tried again with it),
with these flags added to the brief's: `--libreoffice-deny-private-ips=true --libreoffice-max-queue-size=8
--libreoffice-restart-after=10`. LibreOffice converts one file at a time (a lock inside Gotenberg); a full queue answers 503, refused here as
*The converter is busy: try again in a minute.* Running Gotenberg on a Docker network with no route out at all is stronger still (the
owner's, at deploy).

**The API asks Gotenberg its version, and converts nothing below 8.34.0** (`brief/gotenberg.converts_office`, from its own `GET /version`,
kept ten minutes, a minute after no answer): `GOTENBERG_URL` alone is not enough, since the brief prints through older ones, and a
deployment that sets it for the brief while Gotenberg is still 8.21.1 would otherwise hand hostile Office files to a LibreOffice that
fetches what they link to. Below it (or with no answer, or no version in one) an Office file is refused as if there were no Gotenberg
(§4.4). What it makes is read only as far as a PDF may be (50 MB), never whole into memory first.

**What it looks like.** LibreOffice is not Word: a document laid out with fonts the image lacks reflows, and an elaborate PowerPoint can
shift. Whether the image's fonts cover Army templates (Calibri and Cambria need their metric-compatible Carlito and Caladea, Arial and Times
New Roman Liberation's) is checked with the unit's own files before launch (§14); the plan does not promise a page-perfect copy, and the
upload review says so. **A tracked change the file
shows is in the PDF**: accept or reject changes before uploading. The upload screen says both in one line.

### 4.2 A PDF is kept as sent

A PDF is not converted. It is checked (§8.3: it opens, needs no password, has at most 500 pages) and kept byte for byte, so what was signed
is what is shown. A PDF that only restricts printing or copying (an owner password, no user password) opens without one and is taken. Its
JavaScript, forms and attachments are never run or shown: every viewer here is static (§8.1).

### 4.3 The original is not kept

**Decided: the PDF only** (the owner, 2026-11-22). The original Office file is sent to Gotenberg and forgotten; the record names no copy
of it. Why:

- What is kept is exactly what is shown. A Word file's tracked changes and comments, an
  Excel file's hidden sheets, a PowerPoint's speaker notes are **not** in the PDF, and could hold what no reader of the PDF would ever see.
- It is the owner's "not an editor": the Word file lives where it is written; EZ/PZ holds the copy to read.
- Half the storage.

Keeping it would have added `source.sha256`, a *Download original* on each client, and the original's hidden content to every copy.

### 4.4 Where there is no Gotenberg

Without `GOTENBERG_URL` (a bare local run, Fly), or with a Gotenberg older than 8.34.0 (§4.1), **a PDF is still taken**; an Office file is
refused with 503 `conversion_unavailable`: *This server cannot turn Office files into PDFs. Save it as a PDF and upload that.*
`/api/config` says so ahead of time (`services.documentConversion`, a new optional boolean, from the same version check), so a client offers
*PDF* alone in its picker there and says why.

---

## 5. Storage

### 5.1 The asset store

The bytes go in an asset store by SHA-256, as an LZ survey keeps its photos (`survey/assets.py`: `AssetStore`, `LocalAssetStore`, each file
written whole and renamed into place, never changed). The class is reused with a root of its own, **`DOCUMENTS_DIR`**: a persistent mount,
one per deployment (`/data/documents/prod`, `/data/documents/staging`, since their databases are separate), unset meaning the system's temp
directory, lost on redeploy, with a warning at startup in production as `SURVEY_ASSETS_DIR` has. Two kinds of file, both by their own hash:
the PDF (`application/pdf`) and its text (`application/json`, §8.2). One PDF uploaded twice, or copied into three packs, is one file.

The database never holds the bytes (the `.msnx` is a `LargeBinary` column; documents can be fifty times larger and many).

### 5.2 Tables

- **`saved_document`** (`SyncMixin`, synced, §6): `user_id`, `name`, `doc_data` (the JSON of §2), and, for listing and for finding what
  names a file without reading JSON: `pdf_sha256`, `text_sha256`, `page_count`, `pdf_bytes`, `created_at`, `updated_at`.
- A pack's document is an ordinary `mission_pack_item` of kind `document` whose `data` is the JSON of §2 (§7).

`schema_sync` has nothing to add: the table is new, made by `create_all`.

### 5.3 When a file is deleted

A file is deleted once no **live** Library record or pack item names it: after a delete, a new version or an account's deletion
commits, each hash the old content named is looked for in `saved_document`'s two columns and in the live `document` items of every pack
(`kind = 'document' AND deleted_at IS NULL`: a small set), and deleted if nothing names it. A sweep ten minutes after startup and then every day (where `DOCUMENTS_DIR` is set: `documents.files.start_sweeping`) does the same
for every file older than an hour (an upload writes its files just before its row commits), so a crash between the two leaves nothing for
long. A tombstone blanks its content at once, as every saved record's does (`doc_data` to `{}`, the hash columns null, the name empty).

**A copy holds the files lock from reading what it copies to committing** (`files.held()`): a Library document copied into a pack, a pack's
copy updated from its original, a pack's document saved to a Library, a pack duplicated. The delete it races releases its files only after
its own commit and under the same lock, so it either finds the copy naming them or the copy finds the original gone; without the lock a
copy could commit naming a file already deleted (the D1 review proved it). A copy also checks the files are on disk. A new version, which
converts for minutes before it writes, reads its record or item again once it holds the lock and decides again by it: a document deleted
meanwhile stays deleted, one renamed meanwhile is a 409 for the revision sent, and two versions at once are two revisions, the later on
top. A rename and a delete of a Library document lock its row (`FOR UPDATE` in Postgres) as they read it.

### 5.4 Limits

| | Limit | Why |
|---|---|---|
| A file uploaded | 25 MB | An OPORD with annexes as a PDF is a few MB; a heavy PowerPoint 10–20 MB. Under Cloudflare's 100 MB body limit and the 180 s request |
| A PDF kept | 50 MB, 500 pages | A conversion can grow a file; reading the text of 500 pages with pypdf can take tens of seconds |
| Its text | 2,000,000 characters, and 60 s of reading (cut, with a note: `truncated`) | Find in the document is a few hundred KB for a real OPORD |
| A person's Library | 200 documents, 1 GB | Counted from `saved_document`; past it *Your Library is full: delete a document first* (413 `library_full`) |
| A pack | 100 documents, 1 GB | Counted from its live items (413 `pack_full`) |

All five are constants in one module, the owner's to change. **Two uploads are read at once** (`core.READING_AT_ONCE`; a PDF's streams
inflate as pypdf reads them, a few hundred MB for a hostile one, in the worker that also holds SAM); more wait their turn, and after a
minute are told *The server is busy reading other documents* (503 `converter_busy`). A zip's entries are counted from its end record before
anything else reads it (at most 20,000 entries and a 2 MB directory), since Python's reader makes an object for every entry first.
The Library's count and size are checked again under the files lock, so uploads side by side cannot pass the limit together.

### 5.5 A new version

A paper changes (an OPORD's change 1, a revised annex). **A new version** replaces a document's PDF and text under the same name and
identity: `POST /api/documents/<id>/version` in the Library, `POST /api/packs/<uuid>/items/<item>/version` in a pack (an `item.replace` the
server makes, as *Update from original* does, logged *Sam B. replaced "OPORD 24-03" with a new version*). It goes through every check of
an upload. A pack's copy of a Library document then reads *Original changed*, and *Update from original*
takes the new version, with nothing new to build: it is the pack machinery already there (`update_from_original`).

---

## 6. The Library

`SavedDocument` is a new saved-record kind, `document`, synced like the others (`sync_support._KINDS`), with the same four rules
(AGENTS.md §7): a tombstone, never a `DELETE`; every query filters `deleted_at IS NULL`; the cursor is the counter; additive for the web.

| Route | Does |
|---|---|
| `GET /api/documents` | The caller's, newest first: summaries (`id`, `name`, `pages`, `bytes`, `source_type`, `has_text`, times, `client_uuid`, `revision`) |
| `POST /api/documents` | Multipart: `file`, `name` (optional), `client_uuid` (optional; the same twice returns the first, as every create). A heavy request (§9.1). 201 with the summary |
| `GET /api/documents/<id>` | The summary and `data` (the JSON of §2) |
| `PUT /api/documents/<id>` | JSON `{name}`: a rename, with `If-Match` and `Idempotency-Key` as every saved record |
| `POST /api/documents/<id>/version` | Multipart as the create: a new version (§5.5), with `If-Match` |
| `DELETE /api/documents/<id>` | A tombstone; the files go once nothing names them (§5.3) |
| `GET /api/documents/<id>/pdf` | The PDF, `Content-Type: application/pdf`, `Content-Disposition: inline` (`?download=1`: an attachment), `Cache-Control: private, no-cache` with an `ETag` (a new version is a new file at the same address, so a kept copy is asked about with `If-None-Match` and a 304 costs nothing), range requests honoured (pdf.js reads a large file a part at a time), `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`, so the PDF opened at its own address in a browser runs nothing |
| `GET /api/documents/<id>/text` | The page text (§8.2), with the same headers but `application/json` |
| `GET /api/sync/changes` | Gains `type: document`, `data` the JSON of §2 |

Every route needs the `documents` entitlement (§9.3), and a write also `cloud_save`, as the Library's other writes do. All are described in
`contracts/openapi.yaml` and recorded in `contracts/fixtures/network/responses.json` (`backend/tests/test_network_fixtures.py`), Gotenberg
stood in for as the terrain routes' model is.

**The API version.** The feed's `type` and a pack item's `kind` are enums in responses, and widening one is breaking (AGENTS.md §6), as
`threatset` was: **API 3.0.0**, `API_MAJORS_SERVED = (1, 2, 3)` (Android passes over a feed type it does not know, so the older majors stay
served), and each client lists 3 (`frontend/src/apiSupport.json`, `ezpz.apiMajors`) once it reads documents. An installed Android app from
before then refuses a pack's `document` item as malformed (`bad_kind`), as one from before B7 refuses a brief: its copy of the pack lacks
the document and skips its rename and removal. The owner may hold major 2's apps back once enough have updated.

**Changes are made online only, on every client.** The server makes the PDF, so an upload cannot be queued for later with any honesty
about what will be kept, and a document's name is the only thing a person changes. So a device never sends a document through the sync
engine's outbox: upload, rename, new version and delete are calls made while connected (each says *Needs a connection* when it is not), and
the feed brings their result to every device. **Reading works offline** once a document has been opened (§9.2). This keeps the sync engine's
conflict rules (a copy kept beside the server's) out of documents, where a "conflict copy" would be a second record naming the same file.

---

## 7. Mission packs

A `document` item kind (`pack_ops.ITEM_KINDS`, `packOps.js`), whose `data` is the JSON of §2, listed in the Pack panel's Items in a
*Documents* group after the point sets (and the threats), each row with its name, *14 pages · from DOCX*, who added it and when, and the
dot when it changed since the person looked.

**How one gets in.** Never by a client's `item.create`: the server makes it, so the JSON always names files the server checked.

| Route | Does |
|---|---|
| `POST /api/packs/<uuid>/documents` | Multipart, as `POST /api/documents`, plus `item` (the client's id for the new item, so a retry after a lost answer is the same item, as copy-in's is). An editor, the pack not finished. Logs `item.create` with the JSON: *Sam B. added the document "OPORD 24-03".* |
| `POST /api/packs/<uuid>/items` | Copy in from the Library, as today, with `source.kind: document`: the same files, no bytes copied, the source remembered |
| `POST …/items/<item>/version` | A new version (§5.5), an editor; `item.replace` |
| `POST …/items/<item>/update-from-original` | As today, for the member who copied it in |
| `POST …/items/<item>/library` | Save a copy to the caller's Library, as today: a new `SavedDocument` naming the same files (needs `documents` and `cloud_save`) |
| `GET …/items/<item>/pdf`, `…/text` | The files, to **any member, in any state of the pack**, finished included |

**What a client may send** in `POST …/ops` for a document: `item.rename` and `item.delete` only. A batch with an `item.create` of kind
`document`, or a `set`, `patch`, `upsert`, `insert` or `remove` on a document item, is refused whole (400 `invalid_op`, reason `bad_kind`),
a check in `apply_ops` as `_only_briefs` is. `pack_ops` itself only learns the kind, so the server's own `item.create` and `item.replace`
events apply on every client; `contracts/fixtures/packs/ops-document.json` (written by the web, as `ops-threatset.json` was) holds those.

**Who may do what** follows every other item: an editor or the owner adds, renames, replaces and removes; a viewer reads, downloads and
saves a copy to their Library; a finished pack is read only for everyone, but its documents still open, download and copy out. Removing a
document from a pack deletes the pack's item; the files go once nothing names them (another pack's copy, a Library record, keep them).
`item_counts` gains `document`. The history's sentences (`packSentences.js`, `contracts/fixtures/packs/describe.json`): added, renamed,
replaced with a new version, removed, copied from the Library.

`docs/MISSION_PACKS.md` §2's item table, §3's `item.create` line and §7 gain the kind in the same PR.

---

## 8. Viewing and search

### 8.1 The viewers

Every viewer is static: pages drawn, zoom, *page n of N*, find in the document. No link is followed, no form filled, no script run, no
attachment shown.

| | Viewer | Notes |
|---|---|---|
| Web | **pdf.js** (`pdfjs-dist`, Mozilla, Apache-2.0), one exact version (the owner's choice, 2026-11-22) | Loaded only when a document opens (a dynamic import, as Cesium stays out of the main bundle), its worker served from the site's own origin, XFA off and no scripting, annotation layer or links. pdf.js 6 has no eval path at all (CVE-2024-4367 ran script through a font's, behind the `isEvalSupported` option that version removed). Pages drawn on canvases as they scroll into sight, a few either side kept |
| Android | The platform's **`PdfRenderer`**, as the brief's review draws its PDF (`RendererBriefPdfs`) | No dependency. Pages drawn as bitmaps as they scroll into sight; pinch and double tap to zoom |
| iOS | **PDFKit**'s `PDFView` (`docs/IOS_PLAN.md`) | A full viewer; its own search is not used, so results match the others (§8.2) |

**The browser's own viewer** in an `<iframe>` was the alternative: no dependency and a good viewer on a desktop, but Chrome on Android
does not show a PDF inline at all (it downloads it), iOS Safari's is limited, and its look and its search are each browser's.

**Where it opens.** On the web, a window over the map that can be moved by its bar and resized (`react-draggable` is already a dependency),
so the OPORD stays open beside the plan; its size and place are remembered per browser. At 850 px and below it is the whole screen, Back
closing it. On Android a page over the frame (the map and panels stay composed under it, as the route's plan page and the 3D view keep
them), on a phone and a tablet alike for now. Pages stay white in every theme (they are paper); the chrome follows the theme, and the night
palette dims the page under a red-shifted veil, so a white page is never the brightest thing on a night screen (as the grid's QR code is
kept behind a button at night).

### 8.2 Search

Two kinds, both in v1:

- **By name**, in the Library's Documents tab and among a pack's items, as every item is found today.
- **Find in the document**: a box in the viewer; the pages it is found on listed with a few words round each hit (*p. 4 … LZ HAWK at
  grid …*), the count, and a tap goes to that page; ↑ ↓ step through.

**Decided source: the server reads each page's text once, at upload** (the owner, 2026-11-22), into a file of its own, `{version: 1, pages: ["…", "…"]}`,
kept by its hash beside the PDF and fetched with it, so every client searches **the same text the same way**, offline too. The matching
rule is one pure function, ported to each client and held to `contracts/fixtures/documents/search.json`: the query and the text each
folded (Unicode NFKD with combining marks taken out, lower case by the simple mapping, every run of white space one space), a hit counted
where the folded query occurs, the snippet 40 characters either side in code points. Highlighting the hit on the page needs where each
word is drawn, which only pdf.js and PDFKit give; v1 goes to the page, and drawing the highlight is a later step.

The alternative was each platform's own text: pdf.js's text layer, PDFKit's search, and on Android `PdfRenderer`'s text only from Android
15 (API 35), so no find in the document on most of the devices in use today, and three implementations to keep apart.

### 8.3 Reading a PDF on the server: pypdf

Checking a PDF (it opens, it needs no password, how many pages) and reading its text need a PDF library in the API: **pypdf** (BSD, pure
Python, no system packages), a new dependency the owner approved (2026-11-22). It is pinned, goes in
`backend/requirements.txt`, and on `backend.yaml`'s list if its tests import it (AGENTS.md §11). A file is read with a time limit and its
pages counted before their text, so a hostile PDF costs at most the limits of §5.4. PyMuPDF was weighed and left: AGPL. Gotenberg's
`/forms/pdfengines/metadata/read` gives a page count with no new dependency but no text.

---

## 9. Clients

### 9.1 The web (the reference)

- **The Library** gains a **Documents** tab (icon `file`): each row its name, *14 pages · from DOCX · Updated …*, *Open*, and under ⋯
  *Rename*, *New version…*, *Download PDF*, *Add to Mission Pack…*, *Delete* (asked first). *Upload document* in the tab's header.
- **The Import menu** gains *Document (PDF, Word, Excel, PowerPoint)*, and a document dropped on the map goes to the same review: where it
  goes (the Library, or the open pack for an editor), its name, and *Upload*, which waits with a `BusyButton`
  while the server converts. **A `.docx`, `.xlsx` or `.pptx` is a zip**, and the import's reader takes a zip for an AMPS mission unless it
  holds a KML (`feature/imports`): it must look for OOXML's parts (`[Content_Types].xml` with `word/`, `xl/` or `ppt/`) first, or a Word
  file is refused as a broken mission.
- **The Pack panel**'s Items gain the *Documents* group (§7), with *Upload document* for an editor and each row's *Open*, *Rename*, *New
  version…*, *Download PDF*, *Save to Library*, *Remove*.
- `useDocuments` owns the Library's list and the calls; `feature/documents/` holds the viewer (`DocumentViewer`), the upload review and the
  search rule (`documentSearch.js`). An upload is a heavy request (`requestPriority`: the method too, so listing is not).
- `apiSupport.json` lists 3.

### 9.2 Android

- **core-network**: the routes' DTOs, held to the recordings by `DtoFixtureTest`; the upload a multipart call with the heavy read
  timeout (190 s, since D1); holding background calls back while it converts waits for D3, because `PriorityGate` matches by path
  alone and would hold back listing the documents too; the files read bounded (`Call.maxBytes`: 50 MB for a PDF, 9 MB for its text,
  2,000,000 characters of up to four bytes each).
- **core-sync**: `RecordKind.DOCUMENT`, pulled like any record, its document the JSON of §2; **never in the outbox** (§6): the Library's
  rename and delete call the API directly and then ask for a sync. No database version is needed for the kind (Room keeps a kind by its
  name); a device past the feed's `document` entries pulls them because none exist before the server has the kind.
- **core-data**: `DocumentFiles`, the PDFs and texts kept on the device by their hash in `noBackupFilesDir/documents/`, each downloaded
  whole, **its SHA-256 checked against its name** before it is renamed into place, at most 500 MB, the longest unopened let go first;
  *Keep on this device* pins one past that (per account). Everything is cleared at sign-out (a document is the account's), and a document
  deleted is removed from the device when its tombstone arrives. A pack's documents are kept the same way, by hash, so a Library
  document and its pack copy are one file.
- **feature-documents** (new): the viewer page over `PdfRenderer` reading the kept file in place (one page drawn at a time behind a lock, as
  `RendererBriefPdfs` does, its `Error`s caught, §14), the find box over the text, *Share PDF* (the share sheet, a read grant on a copy in
  `cache/exports/`, as the brief's) and *Save to device*.
- **The Library** gains its Documents tab; the **Pack panel** its *Documents* group; **Import** its *Document* entry (the system picker with
  `application/pdf` and the Office types); and a PDF or Office file **shared to the app** (SEND, SEND_MULTIPLE; not VIEW, which would offer
  EZ/PZ for every PDF on the phone) goes to the same review. `FileKinds` must tell OOXML from a mission as the web must.
- The search rule ported and held to `documents/search.json`; `PackOps` learns the kind, held to `ops-document.json`.

### 9.3 Entitlement

A new feature key, **`documents`** (*Documents*: *Upload PDFs and Office files to your Library and Mission Packs and read them as PDFs*),
in **`entitlements.DEFAULT_OFF`** until it launches: off for everyone but admins and the testers ticked for it. Every `/api/documents` route
and a pack's upload and new-version routes need it. **Reading a pack's documents needs only membership**: a pack is shared with people
whatever their features, and a member without the key would otherwise see items they cannot open (a client without `documents` still shows a
pack's documents and opens them). Launching is taking the key out of `DEFAULT_OFF`.

### 9.4 iOS

A section in `docs/IOS_PLAN.md` once that plan is on `develop` (it is being written). What it will say: the Library's and the Pack panel's
documents as on Android; PDFKit's `PDFView` with the shared search rule over the server's text (PDFKit's `PDFSelection` can draw the hit's
highlight, which Android cannot yet); upload from the Files app (`UIDocumentPickerViewController`) and, later, a share extension; and **the
PDF to ForeFlight's Documents** through the share sheet, the same way the plan sends a brief there, since crews carry their OPORD in
ForeFlight on the iPad.

---

## 10. Owner steps (agents do not deploy)

- Deploy Gotenberg at one exact version ≥ 8.34.0 with the flags of §4.1 (and re-check the brief's print with it), or say to keep 8.21.1
  for the brief and run a second Gotenberg for documents.
- Mount `/data/documents/prod` and `/data/documents/staging` and set `DOCUMENTS_DIR` on each API. Decide whether it is backed up off the
  VM: documents can be uploaded again by whoever has the paper, and a backup keeps a deleted one.
- `pip install -r requirements.txt` picks up pypdf.
- Tick `documents` for the testers; take it out of `DEFAULT_OFF` to launch.

---

## 11. Steps

Each as the other features were built: implemented, reviewed independently, fixed, committed with its tests and the build green, AGENTS.md
updated in the same commit, a PR to `develop`.

| Step | What | Depends on |
|---|---|---|
| **D0** | This plan, and the owner's answers (§13) | — |
| **D1 Server** | `saved_document`; `documents/` (the store's root, `convert` through Gotenberg, `pdf` checks and text with pypdf), `/api/documents` and its files, the feed's `document`, the pack routes and the `document` kind (`pack_ops`, `apply_ops`'s refusal, copy in and out, update from original, item files), deletion of files (§5.3) and the sweep, account deletion, `/api/config`'s `documentConversion`, the entitlement; on the web only `packOps.js`'s kind and the fixtures it writes (`ops-document.json`), since the server is held to them, and `packApi`'s calls for the pack's document routes (its test wants one for every pack route in the spec); OpenAPI 3.0.0 with recorded responses, and on Android only core-network's types for them (`DtoFixtureTest` decodes every recording strictly); tests (each limit, each refusal, a hostile PDF, idempotent upload, files deleted only when nothing names them) | D0 |
| **D2 Web** | The Library's tab, the upload review, Import and a drop, the OOXML sniff, the Pack panel's group, the viewer (pdf.js), find in the document (`documentSearch.js`, `documents/search.json`), the sentences (`describe.json`), `apiSupport.json`; USER_GUIDE | D1 |
| **D3 Android** | core-network, `RecordKind.DOCUMENT`, `DocumentFiles`, `feature-documents`, the Library tab, the Pack panel's group, Import and the share target, `FileKinds`, the ports held to the fixtures, `ezpz.apiMajors`; AGENTS.md §17, `docs/HANDOFF.md` | D1 (D2 for the fixtures it writes) |
| **D4 iOS** | The section of §9.4 in `docs/IOS_PLAN.md` | the iOS plan on `develop` |
| launch | Take `documents` out of `DEFAULT_OFF` (the owner) | D2, D3 tried on a device |

---

## 12. Rules carried over

- Unclassified only, no CUI (§3); secrets stay on the server (Gotenberg is internal, with no public hostname); no new dependency without
  asking (pdf.js and pypdf were asked and approved, §13); agents do not deploy (§10).
- Auth-gated responses are `Cache-Control: private` (AGENTS.md §14).
- A file from someone else is hostile input: its type is read from its content, its size bounded before it is read whole, a PDF opened by
  a library with a time limit, and nothing in it run.
- Names are 1 to 100 characters, counted as code points, as every item's.
- The web is the reference: the search rule, the pack kind and the sentences change on the web first, through the fixtures.

---

## 13. The owner's decisions (2026-11-22)

1. **Classification at upload** (§3): **nothing** beyond the rule as it stands; no acknowledgement, no marking check.
2. **The original Office file** (§4.3): **the PDF only.**
3. **The web's viewer** (§8.1): **pdf.js** (`pdfjs-dist`, approved as a new dependency).
4. **Find in the document** (§8.2, §8.3): **the server reads each page's text at upload with pypdf** (approved as a new dependency).

Taken as defaults, for the owner to overturn: the limits of §5.4; changes online only (§6); pack roles as every item's (§7); the key
`documents`, off until launch, reading a pack's documents needing membership only (§9.3); *New version* in v1 (§5.5); no off-VM backup of
the store (§10); the viewer as a movable window on the desktop web (§8.1). Left for later: removing every copy at once (§3.1).

---

## 14. What cannot be verified in an agent session

How a real OPORD, sync matrix and briefing deck look once LibreOffice has converted them (try the unit's own templates with the deployed
Gotenberg before launch); pdf.js over the map in each browser, and on a phone's browser; `PdfRenderer` on a device with a large PDF, and its
memory; the share sheet and *Save to device*; TalkBack in the viewer; Gotenberg's private-IP refusal with a document that links to the VM's
own address.

---

## 15. Cost

Medium. The server is two tables, a module and a dozen routes on patterns already here (the asset store, Gotenberg, the saved-record sync,
pack copy-in); the pack kind is a kind like `threatset`, smaller since a document's content never changes by operations. The web's viewer is
the largest new piece (pdf.js adds about 1.5 MB, loaded only when a document opens). Android is a module and a cache on the brief's renderer.
Storage is the new running cost: at the limits, a busy account is 1 GB, so the VM's disk wants watching (the admin's Data tab shows each
volume's free space).
