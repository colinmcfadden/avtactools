# Plan: EZ/PZ and TAK (ATAK, iTAK and TAK servers)

Status: proposed 2026-10-26; the ways to connect (§3a) added the same day. Planning only: nothing is built. The decisions only the owner can make are in §11, each with
a recommendation. Until they are answered, nothing here is a commitment.

The request, in the owner's words: "an option to connect to a TAK server for access to custom map types, overlays, and
eventually pushing routes, doghouses, LZ surveys, etc for use in ATAK/ITAK." That is two directions:

1. **From TAK into EZ/PZ, first:** the map sources and overlays a unit's TAK setup provides, shown as base maps or
   overlays on the web map (Leaflet) and the Android map (MapLibre).
2. **From EZ/PZ to TAK, later:** planning products (routes, LZ/PZs, doghouses, aircraft, sectors, threats, the LZ card)
   in ATAK and iTAK, as native objects where TAK has them.

**The short version.** Most of what crews want needs no live connection. TAK's formats are files: a map source is an
XML file, an overlay is KML/KMZ, and a TAK *data package* is a zip of CoT events with a manifest. ATAK and iTAK import
them from Files, the share sheet or the TAK server's own file list. So the plan starts with **files both ways** (T1, T2),
which works offline, needs no certificates, puts no TAK credential anywhere near EZ/PZ's servers, and keeps EZ/PZ out of
any network it has no business on. A **live connection from the Android app** comes after (T3), in whichever of
several ways the person's organization supports (§3a: a connection package, enrollment, a username and password, or
Sit(x)'s own sign-in), and only to servers the owner's rules allow (§2). The browser never connects to a TAK server; a
server-side relay for web users is possible but not recommended now (§3).

---

## 1. What TAK is, briefly, and what already touches it

**TAK** (Team Awareness Kit; once Tactical Assault Kit) is a family of clients and servers:

| Piece | What it is | Notes |
|---|---|---|
| ATAK | Android client | Several builds: ATAK-CIV (public: Google Play and the open-source SDK), ATAK-GOV, ATAK-MIL (export controlled, government distribution only) ([tak.gov products][takgov], [ATAK-CIV source][atakciv]). EZ/PZ's own exports can be tried on ATAK-CIV. |
| iTAK | iOS client | Imports KML/KMZ, data packages and map source XML ([App Store listing][itakstore], [COTAK iTAK training][cotakitak]). Fewer features than ATAK. |
| WinTAK, WebTAK, CloudTAK | Windows, the TAK server's own browser client, a third-party browser client | WebTAK uses a client certificate imported into the browser. |
| TAK Server | The official server, Java, PostgreSQL/PostGIS. **Its source is public on GitHub (GPL-3.0), version 4.5.0** ([TAK-Product-Center/Server][takserver]) | Brokers CoT between clients by group, stores files ("Enterprise Sync"), runs Data Sync "missions" (feeds), map layers, federation. |
| OpenTAKServer (OTS) | Open-source Python server, GPL-3.0, active ([OpenTAKServer][ots], [docs][otsdocs]) | Mirrors much of the Marti API (its own list of ~90 routes: [OTS Marti API][otsmarti]). Installers for Ubuntu and Raspberry Pi; an official Docker image is "in the works". |
| FreeTAKServer (FTS) | Open-source Python server, EPL-2.0 ([FreeTakServer][fts]) | Last pushed 2024-10. Partial Marti API; its own docs disagree on some paths ([FTS enterprise sync][ftssync]). |

**CoT** (Cursor on Target) is the XML event every TAK object is: `<event uid type time start stale how><point
lat lon hae ce le/><detail>…</detail></event>`. TAK servers also speak a protobuf form of it on the streaming port.

**What EZ/PZ already has that a TAK crew can use** (no TAK-specific code yet):
- **GPX** of a route (`frontend/src/feature/msnxImport/foreflight.js`, Android `RouteHandoff`, held to
  `contracts/fixtures/routes/handoff.json`). ATAK imports a GPX as a route.
- **Threat KMZ** (`backend/routes/threat_routes.py`, `build_threats_kmz`): markers, detection and engagement rings, and
  the terrain masks as **vector polygons** (`_viewshed_rings`), because ForeFlight will not draw raster overlays. ATAK
  and iTAK import it. Android does not call it yet (`docs/HANDOFF.md` §2, item 7).
- **Not built**: an ATAK data package, KML/KMZ of anything but threats, and anything that reads a TAK file. HANDOFF says
  both "want the owner's say on what ATAK crews expect".

---

## 2. Security and classification: read this first

**EZ/PZ is unclassified only, holds no CUI, and nothing in it is an authorized boundary** (AGENTS.md §2). It runs on
Vercel, a home server behind Cloudflare and Supabase. That decides more of this feature than any technical question.

**Many TAK servers carry CUI.** A military unit's TAK server is typically on a DoD network (or a tactical network) and
holds friendly positions (SA/PLI), chat, mission feeds and files; much of that is CUI, and some servers carry more. Any
of it is information EZ/PZ may not hold. *What a given server carries is not something EZ/PZ can tell from the outside*:
TAK has no machine-readable marking that says "this server is unclassified and holds no CUI". Only the server's owner
(the unit's TAK admin, its ISSM) can say. I could not find a public statement of how Army or DoD enterprise TAK servers
are accredited; the point stands on what they hold, not on a citation.

**Connecting is a separate question from classification.** Even a server with nothing sensitive on it may be on a
network whose rules forbid connecting unapproved software or devices to it. Pushing unclassified planning data *into* a
CUI system is not a spill, but the act of connecting EZ/PZ to that system may break that system's rules. EZ/PZ cannot
settle that either.

So:

| | Rule |
|---|---|
| **Which servers** | EZ/PZ connects only to TAK servers whose owner has said they hold **no CUI and nothing classified** (a training server, a unit's unclassified planning server, a public-safety or demo server), and only if connecting EZ/PZ is allowed there. Recommended (K1): a **list of approved servers per organization**, set by the super-admin with the organization's attestation, and the app refuses any other host. Until organizations exist (`docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md` Part D), only admins and ticked testers have the feature. |
| **What it may pull** | Map source definitions and map layers, KML/KMZ overlays and files the person picks. **Never** the SA feed (other people's positions), chat, contacts, or a mission's CoT wholesale. |
| **What it must never store or relay** | Nothing pulled from a TAK server is sent to or kept on EZ/PZ's servers: not files, not tiles, not CoT, not the server's address book. EZ/PZ's servers are never a bridge between a TAK server and anything else. The client certificate and its key never leave the device (§3). |
| **Files people bring** | A KML, map source XML or data package a person imports is theirs to judge. The import says, once per device, that only unclassified, non-CUI material may be imported (the web's and the app's own words, not a legal notice), as the app's `UNCLASSIFIED` banner does in the sandtable. |
| **Pushing** | EZ/PZ's products are unclassified; pushing them to an approved server is fine. Planning data pushed into a TAK server becomes that server's to handle. |

**Why the streaming port is avoided.** The TLS streaming input (8089) carries everyone's CoT to every connected client
in their groups. A client that connects there *receives the SA feed by default*. EZ/PZ would then hold friendly
positions it has no use for and no right to. Everything planned here uses the **HTTPS API** (8443), which returns only
what is asked for. Sending a single event to a contact uses the API's file share, not the stream (§6).

**Hostile input.** Every TAK file is from someone else and is read as `MsnxReader` reads a mission (AGENTS.md §17): no
`<!DOCTYPE>` or `<!ENTITY>` (refused by text), bounded zip inflation and entry count, entry names kept inside the
package (no `../`), nesting bounded, numbers finite. A map source's URL comes from a file too: only `https:` URLs are
taken, and the URL is never fetched by EZ/PZ's servers (no server-side request forgery to worry about while it is not).

**Third-party tiles.** Community ATAK map source collections ([ATAK-Maps][atakmaps]) often point at Google, Bing or
Esri tile endpoints in ways those providers' terms do not allow. EZ/PZ ships no map source of anyone else's, and the
import says the person is responsible for the terms of the sources they add (K9).

---

## 3. Where the connection lives

| Place | Mutual TLS | Reaches a tactical or private network | Offline | Secrets | Verdict |
|---|---|---|---|---|---|
| **Browser (web app)** | A browser can hold a client certificate (the OS keychain), but page script cannot choose or use one; the browser shows its own picker, per origin, outside the app. The TAK server's CA is private, so the browser refuses its HTTPS unless the CA is installed in the OS. The server sends no CORS headers for `ezpztac.app`. | Only if the laptop is on it | No | The key would sit in the OS keychain, outside EZ/PZ | **No.** It cannot work reliably, and WebTAK already is the browser client for a TAK server. |
| **Flask backend (relay)** | Yes: Python's `ssl` with the client key | **No**: the backend is on the internet (Coolify VM, Cloudflare Tunnel). A tactical network, or any server behind a unit's firewall, is unreachable. | No | The client key would be **stored on EZ/PZ's servers**, one per user or unit: a credential store for another system, and everything pulled would pass through EZ/PZ's servers | **Not now** (K3). The pattern exists (CloudTAK is a server that holds connection certificates and relays: [CloudTAK][cloudtak]), but it puts EZ/PZ's servers in the path of TAK data, which §2 forbids for anything but an approved server, and it reaches only servers on the internet. |
| **Android app (direct)** | Yes: the key in the **Android Keystore** (non-exportable), the server's CA as the trust anchor, OkHttp's TLS with a custom key and trust manager (no new library) | Yes, when the phone is on it (the same Wi-Fi, a tactical radio's IP link, a VPN) | Files work offline; the connection is live only when the server is in reach | The key stays on the device; EZ/PZ's servers know at most the approved hostnames | **Yes** (K2). It is where ATAK runs, where TAK's own clients connect from, and where §2's rules can hold. |
| **iOS / iPadOS app (direct)** | The same, with a `SecIdentity` in the Keychain | As Android | As Android | As Android | Later, with the iOS app (not started). |

**Without a connection at all**, files do most of it (T1, T2): the person gets the map source XML or KML from their TAK
admin, or exports it from ATAK, and imports it into EZ/PZ; EZ/PZ exports a data package and the person opens it in
ATAK or iTAK, or uploads it to the TAK server from ATAK themselves. On Android, a data package goes straight to ATAK on
the same device through the share sheet (`ShareExport`, as the `.msnx` does).

**Getting a client certificate on Android** (T3a; §3a has every way to connect, these two among them):
- **A connection data package** (a zip from the TAK admin with the client `.p12`, the trust store `.p12` and a `.pref`
  file naming the server). Read with the platform's `KeyStore("PKCS12")` and moved into the Android Keystore; the file and
  its password are then discarded. No new library.
- **Enrollment** (username and password): the client makes a key pair, sends a certificate signing request to the
  server's enrollment port, and gets its certificate back. From TAK Server's source ([CertManagerApi.java][certapi],
  [CoreConfig.example.xml][coreconfig]): `GET /Marti/api/tls/config` on port 8446 (HTTPS with basic auth, `clientAuth="false"`)
  gives the subject fields to use, and `POST /Marti/api/tls/signClient/v2?clientUid=…` with the base64 CSR as the body
  returns JSON with `signedCert` and the chain as `ca0`, `ca1`… The key never leaves the Keystore. Android has no public
  API to build a PKCS#10 request, so this needs **BouncyCastle's `bcpkix`** or a hand-written DER encoder (K6).

Ports, from the example `CoreConfig.example.xml`: 8089 TLS streaming CoT, 8443 HTTPS API (client certificate),
8444 federation, 8446 certificate enrollment (no client certificate), and commented-out plaintext inputs (8087, 8080).
A unit's server may use others; the connection data package names them.

---

## 3a. Ways to connect: the person picks what their organization supports

Organizations reach their TAK servers in different ways, so EZ/PZ offers several and the person picks the one their unit
uses. Each is a **connection profile** on the device: a server, a way to sign in, and the credential that way produces,
kept in the Android Keystore. The organization's approved-servers list (K1) says which servers, and which ways, are
allowed. Every way leads to the same few calls (§6: list map layers, find and fetch files, upload a data package), behind
one client interface, so the screens do not care which way was used.

| Way | How it works | Credential on the device | Works with | Phase |
|---|---|---|---|---|
| **1. No connection: files** | The person imports what their TAK admin gives them, and exports data packages they open in ATAK or iTAK (§4, §5). | None | Everything | T1, T2 |
| **2. ATAK's own connection** | EZ/PZ hands a data package to ATAK on the same phone (the share sheet); the crew sends it on from ATAK, which is already connected. EZ/PZ never touches the server. | None | Any server ATAK connects to | T2 |
| **3. Connection data package** | The zip a TAK admin hands out for ATAK. Inside, a `config.pref` names the server (`connectString0` as `host:8089:ssl`) and the client and trust store files (`certificateLocation`, `caLocation`) with their passwords, beside the two `.p12` files ([PyTAK's test package][pytakpref]). EZ/PZ reads it, moves the key into the Keystore, keeps the CA as this connection's trust anchor, and calls the HTTPS API on 8443 with mutual TLS. | Client certificate and key | Official TAK Server, OTS, FTS, Sit(x) (its dashboard hands out "certificate packages": [Sit(x) FAQ][sitxfaq]) | T3a |
| **4. Certificate enrollment** | Username and password (or a one-time token) instead of a certificate. EZ/PZ makes a key pair in the Keystore, asks `GET /Marti/api/tls/config` on 8446 for the subject, and sends a signing request to `POST /Marti/api/tls/signClient/v2` with basic auth; the server answers with the certificate and its chain ([CertManagerApi.java][certapi]). Then as way 3. **ATAK's enrollment link or QR code** carries the same thing: `tak://com.atakmap.app/enroll?host=<host>:<port>&username=<user>&token=<one-time token>` (as OpenTAKServer writes it: [token_api.py][otsqr]); EZ/PZ can take the link pasted, opened from a message, or scanned (K14). | Client certificate and key (the password is used once, not kept) | Official TAK Server, OTS (enrollment and its QR), FTS (to check) | T3a (needs K6) |
| **5. Username and password, no certificate (OAuth)** | TAK Server is an OAuth2 server using the password flow: `POST https://<host>:8446/oauth/token` with the username and password, checked against its file or LDAP/Active Directory back end, returns a JWT signed by the server's certificate ([TAK Server OAuth2 docs][takoauth]). WebTAK signs in this way on 8446 ([WebTAK docs][takwebtak]). EZ/PZ would call the API on 8446 with the token as a bearer. OTS has the same `/oauth/token` route. **Which Marti routes take a bearer token on 8446** is to try: WebTAK's use says the ones it calls do. | An access token (and the password, only if the person asks to stay signed in) | Official TAK Server (with OAuth configured), OTS | T3b |
| **6. Sit(x)'s own API** | Sit(x) is a hosted TAK Server ([Booz Allen][sitxbah], [sitx.io][sitx]), so ways 2 and 3 work with it as with any TAK server. It also has a **REST API with OAuth 2.0** ([Sit(x) developers][sitxdev]): a device authorization grant (the phone shows a code, the person approves it in the Sit(x) dashboard, the phone gets a token), an SSO token grant (SAML, OIDC, OAuth: Entra ID, Okta and others), refresh tokens with rotation, and a "Device / External API" that acts for one user under `{org}.{host}/api/v1/`, covering missions, fileshares, content, GeoChat, users and groups. Access is by **contacting Sit(x)** ("request API access"); the routes and scopes are not public. | An OAuth token for the person's Sit(x) account | Sit(x) only | T3c (needs K13) |
| **7. A VPN on the phone** | Not a way to sign in but a way to reach a server: the unit's VPN, or WireGuard, Tailscale or ZeroTier, makes a private server reachable, and ways 3 to 5 then work unchanged. Nothing for EZ/PZ to build. | The VPN app's own | Any | — |

Not offered: the **streaming port** with a client certificate or an authentication message (TAK Server's `auth="ldap"` or
`"file"` inputs: [authentication messages][takauthmsg]), which carries the SA feed (§2); and **federation**, which is
server to server.

**What Sit(x) changes.** It is the way the owner has used: a managed TAK Server on AWS GovCloud (US), built by Booz Allen
(the Android app's listing names PAR Government as developer), with ATAK, iTAK, WinTAK and its own Sit(x) Mobile app as
clients, mutual TLS and MFA, organizations kept apart in one service, and "accessible from NIPR" ([Sit(x) for defense][sitxdef]).
Its pages state **no FedRAMP authorization, impact level, ATO or CUI rule** ([Sit(x) security][sitxsec]), and none of them
says anything about personal devices. So the Sit(x) tenant an organization uses is judged as any server is (§2): if a
unit lets soldiers use its tenant on personal phones, what the unit says that tenant is approved for is what K1 records.
For EZ/PZ the difference is the API: OAuth instead of certificates, a device grant that suits a phone, SSO, and calls
for content and files. It is also the only way that could serve web users without a certificate, from the backend
(K3), since Sit(x) is on the internet and has a real OAuth service. It needs Sit(x)'s agreement and documentation first.

**Which way where.** Ways 3 to 6 run in the Android app (§3). The browser can use way 1 only. A backend relay (K3) could
use 3, 5 or 6 for servers on the internet, and only for those.

---

## 4. From TAK into EZ/PZ: map sources and overlays

### 4.1 What TAK uses

| Format | What it is | Source |
|---|---|---|
| **Custom map source XML** | `<customMapSource>` with `name`, `minZoom`, `maxZoom`, `tileType`, `url` (tokens `{$z}`, `{$x}`, `{$y}`, `{$q}` for a quadkey, `{$serverpart}`), and optional `serverParts`, `backgroundColor`, `tileUpdate`, `invertYCoordinate`; and `<customWmsMapSource>` for WMS (`layers`, `version`, `coordinateSystem`, `additionalParameters`). Inherited from MOBAC. | [MOBAC custom XML][mobac], [ATAK-Maps][atakmaps], node-cot's reader ([basemap.ts][nodecotbasemap]) |
| **TAK Server map layers** | `GET /Marti/api/maplayers/all` returns the server's layers with the same fields as the XML (`type` is `MapTile` or `WMS`, `url`, `minZoom`, `maxZoom`, `tileType`, `serverParts`, `layers`, `version`, `coordinateSystem`, `invertYCoordinate`, `opacity`, bounds, `defaultLayer`, `enabled`). | [MapLayersApi.java][maplayersapi], [MapLayer.java][maplayer] |
| **Mission layers** | A Data Sync mission's layers (`GET /Marti/api/missions/{name}/layers`), which can hold map layers and files. | [OTS Marti API][otsmarti]; the official shape is in `MissionLayer.java` |
| **KML / KMZ** | Vectors (placemarks, lines, polygons, styles) and `GroundOverlay` rasters. | ATAK and iTAK import both |
| **MBTiles** | SQLite of tiles. | ATAK imports it; OTS and community tools make it |
| **Others ATAK reads** | GeoPackage, GeoTIFF, NITF, MrSID, OSMDroid SQLite tile caches, DTED. | Left out of this plan (§9) |
| **Data packages** | A zip with `MANIFEST/manifest.xml` that may carry any of the above, plus CoT. | §5.2 |

### 4.2 Onto the two maps

EZ/PZ's base maps are a list of XYZ templates (`mapStyles.js`, Android `MapStyles.kt`), under ids a diagram saves. An
imported source joins that list as `tak:<hash of its definition>`; a diagram saved on such a source opens elsewhere on
the default, and keeps its id (the existing rule for an id a version does not know). Overlays are a new kind of layer,
drawn between the base map and the MGRS grid.

| TAK field | Leaflet (web) | MapLibre (Android) |
|---|---|---|
| `{$z}/{$x}/{$y}` | `{z}/{x}/{y}` | `{z}/{x}/{y}` |
| `{$q}` (quadkey) | a small `TileLayer` subclass (`getTileUrl`) | `{quadkey}` |
| `{$serverpart}`, `serverParts` | `{s}` with `subdomains` | one tile URL per part in the source's `tiles` list |
| `invertYCoordinate` | `tms: true` | `scheme: "tms"` |
| `minZoom` / `maxZoom` | `minNativeZoom` / `maxNativeZoom` | the source's `minzoom` / `maxzoom` |
| WMS | `L.tileLayer.wms` (built in) | a raster source with `{bbox-epsg-3857}` in the URL, built from the layer's fields |
| KML vectors | parsed to GeoJSON, `L.geoJSON` | parsed to GeoJSON, a `GeoJsonSource` with line, fill and circle layers |
| KML `GroundOverlay` | `L.imageOverlay` | an image source with four corners |
| MBTiles | read with our own SQLite reader (`localPoints/sqliteReader.js`) into blob URLs: **only small files**, since it reads the whole file into memory | MapLibre may read `mbtiles://` itself (**to verify** on 13.6.1: I found PMTiles documented from 11.7 ([MapLibre PMTiles][mlpmtiles]) but no MBTiles statement); else our own `SqliteReader` behind a local tile handler |

Things that bite:
- **CORS.** The web draws tiles with `crossOrigin="anonymous"` (`MapView.jsx`) so the LZ card's capture can read them.
  A TAK tile server rarely sends CORS headers, and such a tile then fails to load. An imported source is drawn without
  `crossOrigin` and left out of the LZ card's capture (`feature/export/captureFilter.js`), with the card saying so.
- **HTTP.** Many tactical tile servers are plain HTTP. The web cannot draw them (mixed content), and Android's network
  security config allows HTTPS only (AGENTS.md §17). Kept so (K8).
- **A private CA.** A tile server inside a unit's network may use the TAK server's CA. The web cannot draw it; Android
  can, with that CA as the trust anchor of a connection (T3), not of the whole app.
- **Keys in URLs.** A source's URL may carry an API key. That is the person's; it stays on the device (K7).

### 4.3 Where an imported source is kept

At first **on the device only**: the browser's `localStorage` (as `ezpz.mgrsGrid` is) and an Android preference (as the
MGRS grid is). Not synced, not in a plan, not in a mission pack, because a URL may carry a key, a source may only work
on one network, and S6 already left "map overlays" to a library of their own later (`docs/ANDROID_SCREENS_PLAN.md`,
owner decision 2). A KML overlay is a file, held as the session's local points are (this session, or the Library once
that library exists).

---

## 5. From EZ/PZ to TAK: planning products

### 5.1 KML/KMZ or CoT

- **KML/KMZ** is read by ATAK, iTAK, ForeFlight and Google Earth, but ATAK shows it as an overlay: not editable, no
  route navigation, no native objects.
- **CoT in a data package** makes native ATAK objects: a route that ATAK can navigate, shapes a crew can edit, markers
  with remarks. This is what "for use in ATAK/iTAK" means.

So: a **data package of CoT** for TAK (the new work), with the **threat KMZ inside it** for the terrain masks, and the
existing GPX and threat KMZ kept for everything else. How much of a CoT data package iTAK shows (routes especially) must
be tried on a device (§8).

### 5.2 The data package

From ATAK-CIV's own example package ([CoT Types Data Package.zip][atakexamples], "Created by ATAK. Mission Package
version 2"):

```
MANIFEST/manifest.xml
<uid>/<uid>.cot          one CoT event per object
<hash>/<file>            attachments (an LZ card image, the threat KMZ)
```

```xml
<MissionPackageManifest version="2">
  <Configuration>
    <Parameter name="uid" value="…"/>
    <Parameter name="name" value="LZ HAWK"/>
  </Configuration>
  <Contents>
    <Content ignore="false" zipEntry="<uid>/<uid>.cot">
      <Parameter name="uid" value="<uid>"/>
      <Parameter name="name" value="ROUTE 1"/>
    </Content>
  </Contents>
</MissionPackageManifest>
```

`onReceiveImport` and `onReceiveDelete` are optional configuration parameters (node-cot's [data-package.ts][nodecotdp]).
An attachment's `Content` can name the CoT it belongs to (a `uid` parameter), and a KML can carry `contentType`.

**Identities.** ATAK replaces an object with the same `uid`. Every object's uid is a **UUIDv5 of EZ/PZ's own id** (the
LZ/PZ's or route set's id plus the graphic's or point's id), so a second export of the same plan replaces the first in
ATAK instead of adding a copy. The package's own uid is the plan's. Fixtures can then be written with no random ids.

**Time.** `time` and `start` are when it was exported, `stale` far ahead (a plan is not a track), and `<archive/>` so
ATAK keeps it, as ATAK's own drawings do.

### 5.3 Each object

From ATAK-CIV's examples and schemas ([takcot examples and XSDs][atakexamples]); colours are signed 32-bit ARGB integers
(`-1` is opaque white), and KML-style colours inside a shape's `<Style>` are `aabbggrr`.

| EZ/PZ | CoT | Notes |
|---|---|---|
| **Route** | `b-m-r`: one `<link>` per point, in order; a **named (AMPS) point is `b-m-p-w`** with its name as `callsign`, a **shaping point `b-m-p-c`** with none; `<link_attr planningmethod method prefix type color stroke direction routetype order>`; `<strokeColor>`, `<contact callsign>` as the route's name; `<__routeinfo><__navcues/></__routeinfo>` | This matches EZ/PZ's model closely (AGENTS.md §17, *Routes*). ATAK's example uses `method='Driving'`; whether `Flying` is a value ATAK honours is to check (§8). **A route point carries no altitude**: the plan (altitude flown to it, speed, ETA) goes in each link's `remarks`, and how ATAK shows those is to check. |
| **LZ/PZ target** | a marker (`b-m-p-s-m`, a spot, or `u-d-p`) with the name as callsign, the grid, elevation, capacity and the slope call in `remarks` | Whether ATAK has a native HLZ object worth targeting instead is to check. |
| **LZ/PZ boundary** | `u-d-f` (a free-form shape): a closed ring of `<link point="lat,lon"/>`, stroke and fill | The analysis boundary, or the drawn one. |
| **Doghouse** | a marker at its place with its label, heading, time, distance and airspeed in its name and remarks; optionally its box as a turned `u-d-f` polygon | TAK has no doghouse. What a crew expects to see is the owner's (K5). |
| **Aircraft (planned positions)** | **not** an atom type (`a-f-A-M-H…`): a marker (`u-d-p`) named `CHALK 1 · UH-60L (PLANNED)` and its keep-out disc as a circle (`u-d-c-c`, `<shape><ellipse major minor angle/></shape>`, metres) | An `a-f-…` event looks like a live friendly aircraft in everyone's picture (K4). |
| **PZ marker, go-around** | `u-d-f` open lines | No arrowheads in CoT shapes; the KMZ can draw them. |
| **Sector of fire** | `u-d-f` closed wedge | |
| **Unit** | a 2525 marker: the CoT type from its SIDC (`a-f-G-U-C…`), `<usericon iconsetpath='COT_MAPPING_2525B/…'/>` as ATAK's example writes it | Same caution as aircraft (K4). |
| **Threat** | a hostile marker from its SIDC (`a-h-…`), detection and engagement rings as `u-d-c-c` circles in the web's colours, terrain masks as the existing KMZ's vector polygons (attached) | **Only by the person's explicit export** until threats are saved and shared (AGENTS.md §2, step *T*), and its masks need the server's viewshed, which already exists. |
| **LZ survey / LZ card** | the card's image as an attachment of the target's marker | The Excel card is not for ATAK. |
| **Removed object** (pushing to a server, T4) | `t-x-d-d` (a force delete) with the object's uid | node-cot has a builder for it. |

A **mission pack** exports as one data package (its LZ/PZs and route sets), the same as a session's.

### 5.4 Getting it to ATAK and iTAK

| Way | When |
|---|---|
| Download (web) / share sheet (Android) to ATAK or iTAK on the same device, or via Files, AirDrop, a USB cable | T2: no server |
| The crew uploads it to their TAK server from ATAK | T2: no EZ/PZ code |
| EZ/PZ uploads it to the server: `POST /Marti/sync/missionupload?hash=&filename=&creatorUid=` (or `/Marti/sync/upload`) | T3 (Android). Paths from OTS's and FTS's docs and TAK Server's `MissionPackageUploadServlet`; the exact parameters are to record against a real server. |
| … and sends it to contacts or a group: a file-share CoT (`b-f-t-r`) addressed to them | T3; needs the contact list, which is CUI-adjacent: only for an approved server |
| Publish to a Data Sync mission (a TAK "feed"): `PUT /Marti/api/missions/{name}`, then its contents (`PUT …/contents` with hashes or uids) | T4, one-way, from a mission pack |

---

## 6. A live connection, on Android (T3)

What the app does once connected, and nothing more:
- **Pull**: the server's map layers (`/Marti/api/maplayers/all`), and, where the person asks, files from the server's
  file list that are map sources, KML/KMZ or MBTiles (`/Marti/sync/search`, then `/Marti/sync/content?hash=`). They go
  through the same import as a file (§4) and are kept on the device.
- **Push**: a data package (§5.4). Optionally the file-share to a contact or group.
- **Never**: the streaming port, the SA feed, chat, contacts beyond what a file-share needs, mission CoT.

The connection is per device and per account: the certificate, the trust store and anything pulled are wiped at
sign-out, as the threat picture is (`AppViewModel`), and never shown to another account. It is a screen of its own
(*Connect to TAK*: a connection data package, or
host and username for enrollment; the server's certificate fingerprint shown before it is trusted), reached from the
account menu.

---

## 7. Fit with what exists

- **Accounts and the `.mil` gate**: unchanged. TAK is a feature of a signed-in, cleared account.
- **Entitlement**: a new key, `tak`, in `entitlements.DEFAULT_OFF` (as `mission_packs` and `mission_brief`): off for all
  but admins and ticked testers until launch.
- **Organizations** (`docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md` Part D): the natural owner of "approved TAK servers"
  (K1): the super-admin sets a hostname (and optionally a CA fingerprint) and the organization's attestation; the app
  refuses others. Before organizations exist, a server-side list the super-admin keeps for the testers.
- **Mission packs**: a pack exports as one data package (T2); a pack can later be published one-way to a Data Sync
  mission (T4). Two-way sync with a TAK mission is out (§9): a pack's operations, history and conflict rules
  (`docs/MISSION_PACKS.md`) have no counterpart there.
- **API version** (AGENTS.md §6): T1 and T2 need **no new route**; the builders run on the client, as `createMsnx` and
  `foreflight.js` do. T3 needs one additive route, the approved-servers list (in `contracts/openapi.yaml` from the start,
  since the app calls it). A server-side relay (K3) would be several.
- **Contracts and fixtures** (the web is the reference): `contracts/fixtures/tak/`
  - `mapsource/*.xml` and what the web's reader makes of each (and of every hostile case);
  - `kml/*.kml` and the GeoJSON the web's reader makes;
  - `export/*`: a fixed plan (an LZ/PZ with every graphic, two routes, threats) and the data package the web writes,
    compared as parsed documents (as the `.msnx` fixtures are), with fixed times and UUIDv5 ids;
  - every `.cot` checked against ATAK-CIV's XSDs (`takcot/xsd`) by a contracts script (copied into `contracts/` only
    if their licence allows it, which the repository does not state; otherwise fetched by the script at a pinned commit).
  The Android ports (`core-formats`: `TakPackageWriter`, `MapSourceReader`, `KmlReader`) are held to them.

---

## 8. Testing without a real TAK server, and what needs one

| | How |
|---|---|
| Formats (both ways) | Fixtures (§7) and XSD validation; ATAK's example package read back by our reader as a round-trip check. |
| The server's API | **The official TAK Server from its GitHub source, in Docker, on a developer's machine** (it is the reference; OTS second). Record its answers for map layers, the TLS config, enrollment and an upload into `contracts/fixtures/network/` as the existing recordings are, and run the Android client against MockWebServer replaying them, with mutual TLS. |
| Mutual TLS and enrollment | MockWebServer with a test CA and client certificate under Robolectric (the Keystore itself is not testable there: AGENTS.md §17), plus a live test against the local TAK Server when `EZPZ_TAK_URL` is set, skipped otherwise, as `LiveServerTest` is. |
| **Only on devices and a real server** | How ATAK-CIV and iTAK show each object (a route's `Flying` method, link remarks, the doghouse marker, circles); whether iTAK imports routes from a data package; the share sheet hand-off to ATAK; a unit's real server, its ports, its CA and its groups; a tactical network's reach. A short device spike (T0) settles the format questions before T2 is built. |

---

## 9. Not in this plan

- **The streaming port and SA**: never (§2).
- **Two-way sync with TAK missions**, importing CoT from a TAK server as EZ/PZ plans, and TAK chat.
- **An ATAK plugin**: needs the ATAK SDK and TAK Product Center signing; `docs/NATIVE_APPS_PLAN.md` keeps it as research.
- **GeoPackage, GeoTIFF, NITF, MrSID, DTED and WMTS capabilities documents**: later if crews bring them.
- **Federation**, video, and any TAK server of EZ/PZ's own.

---

## 10. Phases

Sizes are one engineer's weeks, ±30%. Each phase ends in something a crew can use.

| Phase | What | Ends in | Size |
|---|---|---|---|
| **T0 — Rules and a device spike** | The owner's decisions (§11). Hand-made data packages tried on ATAK-CIV and iTAK (a route with `Flying` and remarks, a doghouse marker, circles, an attached KMZ), with screenshots into this file. The import's words. | Answers, no code in the product | 0.5 |
| **T1 — Map sources and overlays from files** | Web first: import a map source XML (tile and WMS) as a base map, and a KML/KMZ as an overlay (vectors and ground overlays), through the Import menu and drop; kept per browser; held to fixtures. Then Android (Import menu, the system picker and other apps' share). Small MBTiles on the web; MBTiles on Android once MapLibre's support is known. | A crew loads their unit's map sources and overlays and plans on them | web 2–3, Android 2 |
| **T2 — A TAK data package from a plan** | Web first: Export → *TAK data package* for the session or a mission pack (§5), routes, LZ/PZs, graphics, threats with their KMZ attached, the LZ card image; fixtures and XSD checks. Android: the same, to ATAK through the share sheet. | Plans open in ATAK and iTAK as native, navigable objects | web 2–3, Android 1.5 |
| **T3a — Android connects with a certificate** | The approved-servers route; *Connect to TAK* with a connection data package (way 3), then enrollment by password or `tak://` link (way 4, if K6 allows); pull map layers and map files; push a data package, optionally to contacts. Tried against the local TAK Server, OTS and one real server. | A crew pulls the unit's map layers and pushes a plan from the field, with no laptop | 4–6 |
| **T3b — Username and password (OAuth)** | Way 5 behind the same client interface; which routes take a bearer token recorded against the local TAK Server first. | Units whose server signs people in by password reach it without a certificate | 1–2 |
| **T3c — Sit(x)** | Way 6, once Sit(x) gives API access and documentation (K13): the device grant, SSO, their content and fileshare calls. | Units on Sit(x) connect with their Sit(x) sign-in | 2–3, after access |
| **T4 — A mission pack published to a Data Sync mission** | One-way: create or update the mission, replace its contents, delete what left the pack (`t-x-d-d`). | A team's pack shows up as a TAK feed for the rest of the unit | 3–4 |
| **T5 — Optional** | A server-side connector for web users to internet-reachable approved servers (K3; Sit(x)'s OAuth API is the likeliest), or iOS's T1–T3 once the iOS app exists. | — | 4+ each |

---

## 11. Decisions for the owner

| # | Decision | Recommended | Why | Other choices |
|---|---|---|---|---|
| K1 | Which TAK servers EZ/PZ may connect to | Only servers on a list the super-admin keeps per organization, each with the organization's statement that it holds no CUI or classified data and that connecting EZ/PZ is allowed; none before T3 | EZ/PZ cannot tell what a server holds (§2); a list makes it the owner's call, not each user's | Any server a user enters, with a warning; no live connection ever (files only) |
| K2 | Where the live connection lives | The Android app, directly; never the browser | Mutual TLS with a non-exportable key, reaches tactical networks, no TAK credential or data on EZ/PZ's servers (§3) | Backend relay for everyone; both |
| K3 | A server-side relay for web users | Not now; revisit after T3 if units on internet-reachable training servers ask | It stores other systems' credentials and passes their data through EZ/PZ's servers, and cannot reach tactical networks | Build it in T5 for approved internet-reachable servers |
| K4 | Planned aircraft and units in CoT | Drawn as markers and circles marked PLANNED, never as atom types (`a-f-…`); threats as hostile 2525 markers | An atom event looks like a live track in everyone's common picture | Atom types with `how="h-e"` and a short stale time |
| K5 | What a doghouse is in ATAK | A marker with its fields in its name and remarks, the box as a turned polygon on request; settled by the T0 spike | TAK has no doghouse; what crews read in ATAK is the owner's to say | Name only; leave doghouses out |
| K6 | Certificate enrollment on Android | T3 starts with connection data packages (no new library); enrollment follows with **BouncyCastle `bcpkix`** if approved | Android cannot build a PKCS#10 request without it; most TAK admins hand out connection packages anyway | Hand-written DER (no library, more to keep correct); packages only |
| K7 | Where imported map sources are kept | On the device or browser only, not synced | A URL may carry a key or work on one network only; the Library's overlay kind is already "later" | Synced with the account; in mission packs |
| K8 | Plain-HTTP map sources | Refused, on both platforms | Mixed content on the web; Android's security config is HTTPS-only by design | Allowed on Android per approved server |
| K9 | Third-party map sources | EZ/PZ ships none, imports what the person brings, and says the terms are theirs | Community collections point at providers whose terms forbid it | Refuse known hosts |
| K10 | Order | T1 (map sources in) before T2 (data package out), as asked; both before T3 | The owner asked for TAK into EZ/PZ first, and both are small and offline | T2 first: the data package is the one HANDOFF already lists as not built (`docs/HANDOFF.md` §2, item 7) |
| K11 | Local test server | The official TAK Server (GPL-3.0 source) in Docker; OTS second | It is the reference implementation; OTS is a re-implementation | OTS only; FTS |
| K12 | Which ways to connect, and in what order | Files and ATAK's own connection (ways 1, 2) first; then connection packages and enrollment (3, 4); then username and password (5); Sit(x)'s API (6) once Sit(x) agrees | 3 and 4 work with every TAK server and are what admins already hand out; 5 and 6 need a server configured for them or a vendor's agreement | Sit(x) first, if most units the owner knows use it |
| K13 | Asking Sit(x) for API access | The owner contacts Sit(x) (Booz Allen) now, as research: documentation, a sandbox tenant, whether a third-party app may be registered, and what their tenants are approved for | Only they can say, and the answer decides T3c and whether a web path (K3) is worth it | Wait until T3a is in |
| K14 | Reading a `tak://` enrollment QR code | Take the link pasted or opened from a message first; scanning needs a barcode library (ZXing, or Google's ML Kit, which needs Play services) | No new dependency until crews ask for scanning | Scan from the start |

---

## 12. Dependencies

| Phase | Needs | What it buys |
|---|---|---|
| T1 web | Nothing new: `DOMParser` for XML and KML (with our own hardening), JSZip (present) for KMZ, our SQLite reader for MBTiles. `@tmcw/togeojson` (BSD-2-Clause, small) is the alternative to writing the KML reader. | — |
| T1, T2 Android | Nothing new: `java.util.zip`, the `MsnxXml` DOM toolkit, `SqliteReader` | — |
| T2 web | Nothing new. node-cot ([MIT][nodecot]) is a reference to read, not a dependency: it is built for Node (file system, protobuf, AJV) | — |
| T3 Android | OkHttp (present) with a Keystore-backed key manager; **`org.bouncycastle:bcpkix-jdk18on`** only for enrollment (K6) | Building the certificate request |
| T3 Android, scanning | ZXing (`com.google.zxing:core`, Apache-2.0) only if K14 asks for scanning | Reading an enrollment QR code |
| T3c | Nothing known yet: Sit(x)'s API is plain REST with OAuth 2.0 over OkHttp | — |
| Tests | A TAK Server Docker image built locally (dev only); an XSD validator for the contracts script (`xmllint` from libxml2, or Python `xmlschema`) | Recorded answers; schema checks |

No new major dependency is needed for T1 or T2.

---

## 13. What is uncertain or undocumented

- **The Marti API has no public specification I could read.** OTS says "the full Marti API spec can be found on TAK.gov"
  (an account is needed). The routes here come from TAK Server's source, OTS's list and FTS's docs, which disagree in
  places (`/Marti/sync/upload` against `/Marti/api/sync/upload`). Record them against a real TAK Server before relying on
  them.
- **The manifest has no published schema** beyond ATAK-CIV's examples and what libraries accept. Version 2 is what ATAK
  writes.
- **Route `method` values** (`Flying`?) and how ATAK and iTAK show a link's `remarks`: to try (T0).
- **iTAK's support** for CoT routes in data packages, and KML in Data Sync feeds (a 2024 COTAK note says iTAK did not
  share KML through Data Sync: [COTAK iTAK training][cotakitak]).
- **MapLibre Native's MBTiles support** on 13.6.1.
- **Same-device CoT to ATAK** without a file (UDP to ATAK's input on 4242 or the 239.2.3.1:6969 multicast group, as
  drone apps do: [Auterion ATAK setup][auterion]) is undocumented for loopback on Android; not planned.
- **How DoD and Army TAK servers are accredited**: no public source found; §2 does not depend on it.
- **Sit(x)**: what its tenants are approved for (its pages state no FedRAMP authorization, impact level or CUI rule), its API's routes and scopes (not public), and whether it serves the Marti API to third parties as a stock TAK Server does.
- **OAuth on TAK Server**: whether every Marti route this plan uses takes a bearer token on 8446, and whether FTS enrolls certificates at all.

---

## Sources

- [TAK.gov products][takgov]
- [ATAK-CIV source (archived)][atakciv]
- [ATAK-CIV CoT examples and XSDs][atakexamples]
- [TAK Server source][takserver]
- [TAK Server `CertManagerApi.java`][certapi]
- [TAK Server `CoreConfig.example.xml`][coreconfig]
- [TAK Server `MapLayersApi.java`][maplayersapi]
- [TAK Server `MapLayer.java`][maplayer]
- [OpenTAKServer][ots]
- [OpenTAKServer docs][otsdocs]
- [OpenTAKServer Marti API][otsmarti]
- [FreeTAKServer][fts]
- [FreeTAKServer enterprise sync docs][ftssync]
- [node-cot][nodecot]
- [node-cot `data-package.ts`][nodecotdp]
- [node-cot `xml/basemap.ts`][nodecotbasemap]
- [CloudTAK][cloudtak]
- [MOBAC custom XML map sources][mobac]
- [ATAK-Maps (community map sources)][atakmaps]
- [MapLibre Android PMTiles][mlpmtiles]
- [iTAK on the App Store][itakstore]
- [COTAK iTAK training][cotakitak]
- [Auterion ATAK communications setup][auterion]
- [PyTAK][pytak] (a Python TAK client library, Apache-2.0)
- [PyTAK's test connection preferences][pytakpref]
- [OpenTAKServer `token_api.py` (the enrollment QR link)][otsqr]
- [TAK Server OAuth2 authentication][takoauth]
- [TAK Server WebTAK][takwebtak]
- [TAK Server authentication messages][takauthmsg]
- [Sit(x) on Booz Allen's site][sitxbah]
- [sitx.io][sitx]
- [Sit(x) developers][sitxdev]
- [Sit(x) FAQ][sitxfaq]
- [Sit(x) security][sitxsec]
- [Sit(x) for defense][sitxdef]
- [Sit(x) Mobile on Google Play][sitxplay]

[takgov]: https://tak.gov/products
[atakciv]: https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV
[atakexamples]: https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV/tree/main/takcot
[takserver]: https://github.com/TAK-Product-Center/Server
[certapi]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-core/takserver-war/src/main/java/com/bbn/tak/tls/CertManagerApi.java
[coreconfig]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-core/example/CoreConfig.example.xml
[maplayersapi]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-core/takserver-war/src/main/java/com/bbn/marti/maplayer/api/MapLayersApi.java
[maplayer]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-plugins/src/main/java/com/bbn/marti/maplayer/model/MapLayer.java
[ots]: https://github.com/brian7704/OpenTAKServer
[otsdocs]: https://docs.opentakserver.io/
[otsmarti]: https://docs.opentakserver.io/marti_api.html
[fts]: https://github.com/FreeTAKTeam/FreeTakServer
[ftssync]: https://www.mintlify.com/FreeTAKTeam/FreeTakServer/api/tak/enterprise-sync
[nodecot]: https://github.com/dfpc-coe/node-cot
[nodecotdp]: https://github.com/dfpc-coe/node-cot/blob/main/lib/data-package.ts
[nodecotbasemap]: https://github.com/dfpc-coe/node-cot/blob/main/lib/xml/basemap.ts
[cloudtak]: https://github.com/dfpc-coe/CloudTAK
[mobac]: https://mobac.sourceforge.io/wiki/index.php/Custom_XML_Map_Sources
[atakmaps]: https://github.com/joshuafuller/ATAK-Maps
[mlpmtiles]: https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
[itakstore]: https://apps.apple.com/us/app/itak/id1561656396
[cotakitak]: https://cotak.gov/guest/itak-training
[auterion]: https://docs.auterion.com/vehicle-operation/auterion-mission-control/useful-resources/situational-awareness/atak-communications-setup
[pytak]: https://pytak.readthedocs.io/
[pytakpref]: https://github.com/snstac/pytak/blob/main/tests/data/test_pref.pref
[otsqr]: https://github.com/brian7704/OpenTAKServer/blob/master/opentakserver/blueprints/ots_api/token_api.py
[takoauth]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-core/src/main/webapp/Marti/documentation/takserver/oath2authentication/index.html
[takwebtak]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-core/src/main/webapp/Marti/documentation/takserver/webtak/index.html
[takauthmsg]: https://github.com/TAK-Product-Center/Server/blob/main/src/takserver-core/src/main/webapp/Marti/documentation/takserver/configuration/groupassignmentusingauth/index.html
[sitxbah]: https://www.boozallen.com/expertise/products/sitx-situational-awareness-in-real-time.html
[sitx]: https://sitx.io/
[sitxdev]: https://sitx.io/developers
[sitxfaq]: https://sitx.io/faq
[sitxsec]: https://sitx.io/security
[sitxdef]: https://parteamconnect.com/use-cases/defense-and-military
[sitxplay]: https://play.google.com/store/apps/details?id=com.partech.sitx_multiplatform
