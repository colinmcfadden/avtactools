# Sending grids to people — plan

Status: **proposed, waiting on the owner's choice** (2026-11-04). Nothing here is built yet. The owner has since said how option 4b's
notification reads on Android (§3, option 4).

The owner asked to be able to "send grids to people quickly within the application (on both android and web)", and on Android "via other modes
(like airdrop or bluetooth or something)". This file lays out the ways to do it, what each costs, and a recommendation. §6 lists the questions
for the owner. Where this plan and the code disagree once it is built, the code wins (AGENTS.md §16).

---

## 1. What a shared grid says

Every way of sending below carries the same text, so the person receiving it can read it whatever they open it with:

```
LZ HAWK
16S GC 28864 55349
34.54568° N, 84.12346° W
https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK
```

- **The label** is optional: an LZ/PZ's name, a route point's, a threat's. A bare point under the crosshair has none.
- **The grid** is the app's own (`utils/mgrs.js` on the web, `MgrsConverter` on Android): five digits, truncated, the zone zero-padded, as
  PyGeodesy writes it (AGENTS.md §13). It comes first because crews work in MGRS.
- **The lat/long** is decimal degrees with hemisphere letters, five places (about a metre), which every EFB, ATAK and Google Maps reads.
  The app's own parser reads it back exactly (the target field's dropdown).
- **The link** opens EZ/PZ at the grid (§3, option 3). The grid goes after a `#`, not in a `?` query: a fragment is never sent to a server,
  so the place stays out of Vercel's and Cloudflare's logs.

Unclassified only (AGENTS.md §2): this is a place and a name, which the app already saves and syncs. Nothing about the sender goes with it.

## 2. Where it is offered

The same *Share* action wherever a single place is shown, so it is always a tap or two away:

| Where | Web | Android |
|---|---|---|
| The target field's dropdown (the as-you-type reading) | a share button on each row | the same, in the map's search |
| The map's long-press / right-click menu | beside *Copy grid* | beside *Copy grid* |
| The open LZ/PZ (its target, with its name) | the LZ/PZ panel | the LZ/PZ panel's card |
| A held route point, local point or threat | its inspector | its held bar |
| The grid readout under the crosshair | — | a long press on the pill (a tap already copies) |

## 3. The options

### Option 1 — The system share sheet (and *Copy*)

**Android:** an `ACTION_SEND` intent with `text/plain` (the text in §1), through `Intent.createChooser`. That one intent reaches everything
the phone has: **Quick Share** (Android's nearby sharing, phone to phone over Bluetooth and Wi-Fi Direct, **no signal or SIM needed**),
Bluetooth, SMS, Signal, WhatsApp, mail, ATAK's chat if installed, and *Copy*. Android has no AirDrop; Quick Share is its equivalent, and since
2024 it also reaches Windows PCs. (Android Beam, over NFC, was removed in Android 10.)

**Web:** `navigator.share({ title, text, url })` where the browser has it: Safari on iOS and macOS (which reaches **AirDrop**, Messages and
mail), Chrome and Edge on Android and ChromeOS, Edge and Chrome on Windows (Nearby Share and mail). Firefox on the desktop has none: there the
button is *Copy* (the Clipboard API), with a toast that says what was copied.

- **Cost:** about a day on each platform. **No dependency, no server work.** The app shares the same way already (route exports, `.ths`).
- **Offline:** yes on Android (Quick Share, Bluetooth). On the web, AirDrop works offline between Apple devices; the rest wants a signal.
- **Risk:** none new. The person picks the recipient in the system's own sheet; the app sends nothing itself.

### Option 2 — A QR code on the screen

The sender shows a QR code; another phone's camera reads it, offline, whatever its platform, with no pairing. Good for a cockpit or a
briefing room where nobody shares a network.

- **It carries the text in §1**, not only the link, so a phone with no signal still shows the grid and lat/long when its camera reads it
  (both iOS's and Android's cameras show a text code's words and make its link tappable).
- **Web:** free. The app already ships `qrcode` (the threat and ForeFlight exports draw their codes with it).
- **Android:** the app has **no QR library**. Two ways, either needs the owner's yes:
  - **ZXing core** (`com.google.zxing:core`, Apache 2.0, pure Java, about 550 KB before R8 shrinks it, the de facto standard). A new
    dependency (AGENTS.md §2).
  - **Vendor a small generator** (Nayuki's QR Code generator, MIT, one file, encode only), ported to Kotlin and held to the web's codes by
    decoding both in a test. No dependency; more code that is ours to keep correct.
- **Reading a code inside EZ/PZ** (so a scan opens the grid in the app rather than the camera app) needs a camera and a decoder (CameraX and
  ZXing or ML Kit; ML Kit needs Play services). **Not proposed now**: the phone's own camera app reads the code and its link opens EZ/PZ.
- **Cost:** half a day on the web; one to two days on Android, depending on the library choice.

### Option 3 — A link that opens EZ/PZ at the grid

`https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK`. The web reads the fragment as it loads (as it reads `?invite=` today,
`feature/missionPacks/inviteLink.js`), takes it out of the address bar, flies the map there and drops a marker with *Set as target*. A grid
that does not read says so in words. Nothing is created or saved until the person chooses.

- **Android:** the app's verified link filter already names `https://ezpztac.app/` (the emailed sign-in links). It opens the app at the grid
  **once the site serves `/.well-known/assetlinks.json`** for the app's package and signing key (an owner step that waits on the
  `applicationId`, AGENTS.md §17). Until then the link opens the browser, which opens the web app at the grid.
- **Signed out:** the grid waits through sign-in, as an invitation link does.
- **Cost:** about a day on the web, a day on Android. No server work. It is also what options 1 and 2 put in the text, so it is worth
  building with either.

### Option 4 — Sending to a person in the app

Two shapes, with very different costs:

- **4a. Into the open Mission Pack** as a one-point point set. **No server work**: packs already hold point sets, every member gets them
  through the pack's own sync, and the history says who added it. But it reaches only a pack's members, and **Android does not draw a pack's
  point sets yet** (`docs/ANDROID_MISSION_PACKS_PLAN.md`, *Deferred* 4), so it waits on that.
- **4b. To a teammate directly**, outside any pack: a new server table (sender, recipient, grid, label, sent, seen), routes to send, list and
  dismiss, the people search limited to teammates as it is today, an inbox in each app, and a new notification kind in the outbox
  (`docs/NOTIFICATIONS.md`). **On Android the notification shows the grid** (the owner, 2026-11-04): *Sam B. sent you a grid.* and, on a
  new line, the grid (`16S GC 28864 55349`), and a tap opens the app at the point. That is a deliberate exception to the outbox's rule that
  a row names people, packs and teams only (AGENTS.md §4, §7), because the grid is the whole message and it is unclassified: the row keeps
  the grid in its `fields`, it shows on the lock screen like every notification's words, and nothing else about the place (the label, the
  lat/long) goes in it. A new route the apps call is a minor API version (AGENTS.md §6).
- **Cost:** 4a about two days once Android draws a pack's point sets. 4b about a week across the server, the web and Android.
- **Offline:** neither. Both go through the server.

### Option 5 — ATAK

A point as a **KML placemark** (`.kml`, a few lines: name, description with the grid, the point) through the share sheet. ATAK registers for
KML and imports it as a marker; Google Earth and most map apps open it too. A **CoT** event (`.cot` XML) is ATAK's native form and keeps the
point's type, but nothing but TAK reads it, and ATAK's handling of a lone `.cot` from the share sheet varies by version: KML is the safer first
step. An ATAK data package (a zip with a manifest) is the plan's later work for routes and threats (`docs/NATIVE_APPS_PLAN.md`).

- **Cost:** about a day on Android (and a *Download .kml* on the web). No dependency.
- **Not verifiable here:** what ATAK makes of it. It wants one try on a device with ATAK installed.

## 4. Recommendation

1. **Build options 1 and 3 together first**: the share sheet with *Copy* on both platforms, carrying the text in §1 with its link, and the
   web reading the link. That gives Quick Share and Bluetooth on Android and AirDrop from Apple devices, offline, in about three days, with no
   dependency and no server work. It answers the owner's request as asked.
2. **Then option 2, the QR code**: free on the web; on Android, the owner's choice of ZXing core or a vendored generator.
3. **Then option 5, KML to ATAK**, once someone can try it on a device with ATAK.
4. **Option 4 last**, and 4a before 4b: it needs a signal anyway, and 4a costs little once Android draws a pack's point sets.

## 5. What stays true whichever is built

- One function makes the text in §1 on each platform, held to a fixture (`contracts/fixtures/coords/share.json`, reference the web), so a
  grid shared from Android and one shared from the web read the same.
- The app never sends anything itself in options 1, 2, 3 and 5: the person chooses the recipient in the system's sheet or by showing their screen.
- A label is the person's own text: it is cut to a sensible length (60 characters, by characters, never splitting an emoji) and escaped in
  the KML and the link.
- Nothing is shared from a threat unless the person asks, as today.

## 6. Questions for the owner

1. Which options to build, and in what order? (Recommended: 1 and 3, then 2, then 5, then 4.)
2. For the QR code on Android: **ZXing core** as a dependency, or a **vendored generator**?
3. Should the shared text include the link at all while Android's verified links are not set up? (It still opens the web app.)
4. For option 4b, who may receive: teammates only (as the people search is today), or anyone in a pack with you too?
5. KML or CoT for ATAK, if anyone has seen what their ATAK build does with each?
