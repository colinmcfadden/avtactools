# Place search — plan

Status: **proposed, not built** (2026-11-05). The owner asked how hard it would be to add addresses and searchable points of interest,
so that a crew can type a name and get its grid. This file plans it for the web and Android. §12 asks the owner the questions that must
be answered before anything is built. Where this plan and the code disagree once it is built, the code wins (AGENTS.md §16).

**In short.**
- **It is a search, not a map layer.** A name typed into the field that already reads grids and lat/longs (PR #140) lists the places it
  could be, each with its grid. A map layer of the same points is an option on top (§8).
- **First, two sources:** the FAA's airports, heliports, navaids and fixes (NASR), and USGS's place names (GNIS).
  - Both are free and public domain.
  - Both together are about 100 MB as a search index, and about 35 MB to download. That is small enough for Android to carry whole.
  - So on Android a search works at a FARP with no signal, and the text typed never leaves the device.
- **Addresses and businesses are a separate decision** (§5).
  - **Mapbox's terms rule it out for EZ/PZ.** They forbid showing a geocoded coordinate to the person, storing one, and using one for
    in-flight navigation. The point of this feature is all three.
  - Self-hosting an OpenStreetMap geocoder would cost the VM tens of GB of disk and 8 GB of RAM or more.
  - The recommendation:
    - leave street addresses out;
    - if crews want the landmarks GNIS no longer holds (schools, churches, towers, stadiums), add them later from Overture Maps' open
      places data, into the same index.
- **Built and refreshed as Data tab jobs** (`/admin/data`), as the VFR charts are (docs/VFR_CHARTS.md §5).

**How sure each number is.**
- *Measured*: read on 2026-11-05 from the source named, by a ranged `GET`, a bucket listing, or files downloaded and counted.
- *Read*: taken from the documentation named.
- *Estimate*: worked out, not measured.

---

## 1. What a crew gets

Typing `DZJ`, `blairs`, `Brasstown Bald` or `neel gap` into the top bar's search (web) or the map's search (Android) lists the places that
match, under the coordinate readings the field already gives. For example, `blairs` with the middle of the map at 34.80° N, 83.90° W in Union County, Georgia:

```
Blairsville                                  Populated place · 10 km
17S KU 29607 63307 · Union Co., GA

DZJ  Blairsville                         Airport · 1,907 ft · 11 km
17S KU 25962 60998 · Blairsville, GA

1GA3  Union General Hospital   Heliport, private · 1,900 ft · 11 km
17S KU 29302 64328 · Blairsville, GA
```

(Every value here is from the 10-29-2026 NASR cycle and GNIS's 2026-11-02 file, with grids from `utils/mgrs.js`.)

**What a row says:**
- **The name** as the source spells it. For aviation the identifier comes first, and the NASR city is searched too, so `blairs` finds the
  hospital heliport.
- **What it is**, in words: *Airport*, *Heliport, private*, *VOR/DME*, *Fix*, *Summit*, *Gap*, *Ridge*, *Lake*, *Populated place* …
  - NASR gives an elevation in feet, which the row shows.
  - **GNIS gives none.** Its current files dropped elevation in 2021 (§4.2). A summit's height comes from the slope analysis once it is a
    target, never from a made-up value (AGENTS.md §14, *Never invent a value for "no data"*).
- **Its MGRS grid**, computed on the device from the source's lat/long (`utils/mgrs.js`, Android's `MgrsConverter`), never by the API.
  Then where it is: the NASR city and state, or the GNIS county and state.
- **How far it is** from the middle of the map, in km. This matters: GNIS holds 443 features named *Mount Pleasant* (measured), seven of
  them in Georgia.

**A grid or lat/long always wins.** Text that reads as a coordinate (§2.2) lists its readings first, as it does today, and places only
if a name matches too.

**Choosing a row:**
- It flies the map there: zoom 14 for an airport or a populated place, 15 for anything smaller.
- It puts the place's grid in the field.
- **It does not set the target by itself.** A town's or a summit's point is a reference point for the feature, not a landing zone.
- On the web, a toast says the name and grid and offers *Set as target*, exactly as a grid link does today (`showGridLink` in `App.jsx`).
- On Android, the map search already flies and sets no target (AGENTS.md §17, *The map*), and the LZ/PZ panel's *New LZ/PZ at the
  crosshair* is one tap away.
- Coordinate readings are unchanged: on the web they still set the target when chosen (§12, Q1).

**Sharing a row** uses #142's share sheet, with the place's name as the label (`shareText({ mgrs, lat, lon, label })` on the web,
`GridShare` on Android). It sends four lines:

```
Brasstown Bald
17S KU 43095 62696
34.87419° N, 83.81065° W
<the link>
```

---

## 2. Where it fits in the code

### 2.1 Today (PR #140, #142, #147)

- **Web.**
  - `TargetField.jsx` is an ARIA combobox. As the text changes it calls `readTarget` (`utils/targetReading.js`) and lists the choices as
    rows `{ key, choice, main, note }`, nearest the map first (`nearestFirst`).
  - `TopBarSearch.jsx` opens it Spotlight style over the map.
  - Choosing calls `chooseTarget` in `App.jsx`, which starts an LZ/PZ there.
  - Each row has a share icon (pointer only) and the field has a share button, both through `shareAt`.
- **Android.**
  - `PlaceSearch.read` (core-geo) is the port, held to `contracts/fixtures/coords/reading.json`.
  - `placeRows` and `PlaceList` (feature-map) lay out the rows, and `MapSearchSpotlight` opens the search centred over the frame.
  - A choice flies the map and sets no target.

Both take only a `PlaceChoice` (`{ lat, lon, mgrs, latLong, how }`) today.

### 2.2 What changes

**One more kind of row.** A `PlaceChoice` gains an optional `place`:

```
place: { id, source, kind, kindWords, name, ident?, city?, county?, state?, elevationFt? }
```

- `how` stays the words under a coordinate reading.
- A place row's `main` is its name, with the identifier first for aviation. Its `note` is its grid and where it is. Its kind and distance
  are drawn at its end.
- A screen reader hears it as one option: "DZJ, Blairsville, airport, 1,907 feet, 17S KU 25962 60998, 11 kilometres". The three rows above are in the order §2.2's ranking gives: all start with the text, so the nearest comes first.

**When names are looked up.**
- Only once the text could be a name: at least 2 characters, and not a whole grid, a grid being typed (`16S G`) or a coordinate that
  `readCoordinate` accepts.
- Text such as `34S` is both a band being typed and a word. Today it says *keep typing*. With places it also lists any names that start
  with it.
- `readTarget`'s own answer is unchanged, so `reading.json` holds as it is. The field appends the place rows; `readTarget` does not.

**As it is typed.**
- On the web, a lookup waits for 150 ms of no typing, since it goes to the server. On Android it runs on every keystroke, on the device.
- An answer for older text is dropped.
- The coordinate rows stay while names are looked up, so nothing jumps under the pointer.

**Ranking.** One rule on both platforms, held to a fixture (§11):
1. **An identifier typed whole** (`KATL`, `ATL`, `DZJ`, `1GA3`, `BOBBY`) comes first, whatever the distance.
2. **Then names:** equal to the text; then starting with it; then with a word starting with it, so `gap` finds *Neel Gap*. NASR's city counts
   as a name for its airports and heliports.
3. **Within each group:** nearest the map first, then aviation before GNIS, then by name.

Other rules:
- At most 8 place rows.
- Matched without regard to case, accents (`Peña` by `pena`) or punctuation (`St. Marys` by `st marys`).
- No spelling tolerance. *Neels Gap*, as the gap is often called, does not find GNIS's *Neel Gap*, though `neel` does. Aliases are a
  question for later (§12, Q8).

**Not in this plan:** a search that understands an address's parts; a "what is here" lookup from a right-click or long press (reverse
geocoding); routing; and anything outside the US and its territories.

---

## 3. The sources, side by side

| | FAA NASR | USGS GNIS | Mapbox | OSM self-hosted (Photon) | Overture places |
|---|---|---|---|---|---|
| **What** | Airports, heliports, seaplane bases, navaids, fixes | Named natural features, populated places, administrative areas | Addresses and places (Geocoding v6); businesses too (Search Box) | OSM addresses, businesses and places | Businesses and landmarks |
| **Rows (US)** | 91,102 (measured) | 974,033 (measured) | — | — | 15–25 M (estimate) |
| **Licence** | Public domain | Public domain | Mapbox's terms: **not usable here** (§5.1) | ODbL, with attribution | CDLA-Permissive-2.0, Apache-2.0, CC0 |
| **Updates** | Every 28 days, posted 29 days early | "Irregular", about monthly | Live | Weekly dumps | Monthly releases |
| **Searched where** | Server (web), device (Android) | Server (web), device (Android) | Mapbox, through our API | The VM | Server, and on the device if small enough |
| **What leaves the device on Android** | Nothing | Nothing | The text, to our API, then to Mapbox | The text, to our API | Nothing, or the text to our API |
| **Server disk** | About 10 MB (estimate) | About 99 MB (measured) | None | 25–35 GB, plus as much while updating (estimate) | 200–400 MB for a filtered subset (estimate) |
| **Server RAM** | None to speak of | None to speak of | None | 8–16 GB (estimate) | None to speak of |
| **Android disk** | About 10 MB | About 99 MB | None | None | Not decided |
| **Recommended** | **Yes, first** | **Yes, first** | No | Not now | Later, if crews ask |

---

## 4. The first two sources

### 4.1 FAA NASR (aviation)

**What it is.** The FAA's 28-day National Airspace System Resources subscription: every airport, heliport, navaid, fix, airway and piece
of airspace, as CSV and fixed-width text.

**Where it comes from** (measured):
- **The keyless APRA API** says which cycle is current and which is next, with the zip's URL:
  `https://external-api.faa.gov/apra/nfdc/nasr/chart?edition=current` (or `next`). It answers JSON.
- **The CSVs** are under `https://nfdc.faa.gov/webContent/28DaySub/extra/`:
  - all of them in one zip, `29_Oct_2026_CSV.zip` (21.96 MB);
  - or by area: `29_Oct_2026_APT_CSV.zip` (8.50 MB), `_NAV_CSV` (0.66 MB), `_FIX_CSV` (3.81 MB).
- The whole subscription (`28DaySubscription_Effective_2026-10-29.zip`, 259.4 MB) is not needed.
- **`HEAD` answers 503** from Akamai. A ranged `GET` (`Range: bytes=0-0`) gives the size. The job's download must use that.

**Cycles** (measured):
- The current cycle took effect on **2026-10-29**. The next, **2026-11-26**, is already posted.
- Each cycle's files are posted **about 29 days early** (`Last-Modified` 2026-09-30 for 10-29, 2026-10-28 for 11-26).
- So a build can be made and switched to exactly at the effective date, as the charts' are (docs/VFR_CHARTS.md §5.7).

**What is taken from it.** Rows counted in the 10-29 cycle:

| File | Rows | Taken | Columns used |
|---|---|---|---|
| `APT_BASE.csv` | 19,434 | Airports 13,005, **heliports 5,712**, seaplane bases 562; ultralight, glider and balloon ports (155) too, as they are landing sites | `SITE_TYPE_CODE`, `ARPT_ID`, `ICAO_ID`, `ARPT_NAME`, `CITY`, `STATE_CODE`, `LAT_DECIMAL`, `LONG_DECIMAL`, `ELEV`, `FACILITY_USE_CODE`, `OWNERSHIP_TYPE_CODE`, `ARPT_STATUS`, `MIL_LNDG_FLAG` |
| `NAV_BASE.csv` | 1,608 | All: VOR/DME 411, VORTAC 382, NDB 358, DME 164, TACAN 155 … | identifier, name, type, city, state, lat/long, elevation |
| `FIX_BASE.csv` | 70,060 | All, including the 673 VFR waypoints | identifier, state, lat/long, use code |

**What the row says of aviation:**
- **Private or public use.** Of the 5,712 heliports, 5,655 are private use and 2,778 have *HOSP*, *MEDICAL*, *HEALTH* or *CLINIC* in their
  name. A crew needs to know a pad is private before it is planned as anything.
- **Military** where `OWNERSHIP_TYPE_CODE` says so (99 heliports: MR 76, MN 14, MA 5, CG 4).
- **Closed** (`ARPT_STATUS` CI or CP, 16 heliports) is shown in the row and ranked last, never left out: a closed pad is still a place.
- **Fixes have no name of their own**, only an identifier (`BOBBY`). They are found by identifier alone, and only from 3 characters.

**Licence.** The data is US Government work, so it is public domain (17 USC §105). Nothing on the FAA's page says otherwise. The row's
source line says "FAA NASR, effective 2026-10-29" all the same, so a crew knows which cycle it came from.

### 4.2 USGS GNIS (place names)

**What it is.** The Geographic Names Information System, the federal register of official names.

**Where it comes from** (measured, `https://prd-tnm.s3.amazonaws.com/?prefix=StagedProducts/GeographicNames/`):
- **`DomesticNames/DomesticNames_AllStates_Text.zip`**: 45.8 MB, 147 MB unzipped. It holds one pipe-delimited UTF-8 file (with a byte
  order mark) per state and territory. Each state also has its own zip, from 44 KB (AS) to 2.7 MB (CA).
- Every current file is dated **2026-11-02**. The metadata says updates are "Irregular"; the dates suggest about monthly.
- The columns: `feature_id`, `feature_name`, `feature_class`, `state_name`, `county_name`, `map_name`, `prim_lat_dec`, `prim_long_dec`,
  and the source's coordinates and dates.
- **No elevation.** The 2021 files had `ELEV_IN_M` and `ELEV_IN_FT`; the current ones do not.

**What is in it: 974,033 features** (measured), names 14 characters long on average, in 43 classes:

| Group | Classes and rows |
|---|---|
| Terrain a crew navigates by | Summit 69,337, Ridge 14,783, **Gap 8,393**, Valley 69,454, Cliff 4,525, Flat 9,992, Cape 15,747, Island 17,737 |
| Water | Stream 234,890, Reservoir 73,006, Lake 70,153, Spring 35,013, Canal 21,546, Bay 13,734, Swamp 7,700, Falls 2,533 |
| Places | **Populated Place 185,316**, Crossing 3,293, Military 2,973 |
| Administrative areas | Civil 64,969, Census 14,749 |

**What is no longer in it.** In 2021 USGS archived the structure classes and stopped keeping them up (USGS and the OSM wiki). The current
files have no Airport, Tower, Bridge, Dam, Mine, Locale, Church, School, Hospital, Park, Building, Cemetery, Post Office, Trail or Tunnel.
- Georgia shows the scale: **62,632 features in the 2021 file, 23,738 now** (measured).
- The 2021 Georgia file had Church 11,119, Cemetery 6,379, School 6,195, Building 4,028, Dam 3,284, Tower 548, Airport 546 and Hospital 344.
- The 2021 snapshot is still downloadable (`Archive/MainDomestic/NationalFile.zip`, 85.7 MB), but it is five years stale and will not be
  updated. **This plan does not use it.** A tower or a school that has gone, shown as there, is worse than none. §5.3 is where landmarks
  could come from instead.

**What is taken.** Every class but *Civil* and *Census*, which are administrative areas. They repeat what *Populated Place* already says,
as *City of Blairsville* beside *Blairsville* (§12, Q4). That leaves about 894,000 features (estimate from the counts above).

**Licence.** The metadata's use constraint reads "None. Acknowledgment of the U.S. Geological Survey would be appreciated", and the data
is US Government work. The credit is given: "USGS GNIS, 2026-11-02".

### 4.3 One index for both

One SQLite file per build, `places-<build>.sqlite`, made by the job (§10) and read by both the API and Android. The schema (sketch):

```
meta(key, value)            -- schema version, NASR cycle, GNIS date, counts, built-at
place(rowid, id, source, kind, name, ident, icao, city, county, state, lat, lon, elev_ft,
      use, status, military, rank)
place_fts USING fts4(name, ident, city, content="place", tokenize=unicode61 "remove_diacritics=2",
                     prefix="2,3")
```

- **FTS4, not FTS5.** Android's own SQLite has FTS4 on every version the app supports. FTS5 is guaranteed on none, and bundling our own
  SQLite would be a new major dependency. Python's `sqlite3` writes FTS4, so one file serves both.
- **The size** (measured): an FTS5 index of all of GNIS's 974,033 names, with prefix indexes and diacritics removed, came to **99 MB**,
  and 30 MB gzipped. FTS4 is about the same (estimate, to confirm in P0). NASR's 91,102 rows add about 10 MB (estimate).
  - A test build took 4 s on the Mac.
- **The grid is not stored.** Every client computes it from `lat` and `lon`, so the index never disagrees with the field's own grids.
- **`rank`** is a small number set when the index is built, so a public-use airport comes before a private pad of the same name, and a
  populated place before a stream.
- **The file is public data.** Anyone may have it; it holds nothing about EZ/PZ's users.

---

## 5. Addresses and businesses

The owner asked about addresses and points of interest. NASR and GNIS hold neither street addresses nor businesses. Three ways were
weighed. The recommendation is **none of them now**, and §5.3 later if crews ask for landmarks.

### 5.1 Mapbox, through our API: ruled out by its terms

EZ/PZ already has a Mapbox account for the base maps, so this looked like the cheap way. Mapbox's *Product Terms* (last updated
2026-10-20, section 2.7, read from `mapbox.com/legal/service-terms`) rule it out:
- **2.7.1 forbids using geocoding results for in-flight navigation**, and forbids displaying their latitudes or longitudes directly to
  end users. Both rules apply to temporary and permanent geocodes. A grid is a latitude and longitude written another way, and showing
  the grid is the feature's whole point.
- **2.7.2 forbids storing or caching temporary geocodes.** An LZ set from a result, saved and shared in a mission pack, is storing.
- **2.7.3 lets permanent geocodes be stored**, but only as an incidental feature, with a request per end-user account, and still never
  showing the latitude or longitude.
- **2.7.5 lets POI results be used only with a Mapbox map.**
- The Geocoding API's docs add that v6 "no longer provides POI data". Businesses would need the Search Box API, whose results are for
  temporary use only.

**Price, for the record** (read from `mapbox.com/pricing` on 2026-11-05):
- temporary geocoding: 100,000 free a month, then $0.75 per 1,000;
- permanent geocoding: no free tier, $5.00 per 1,000;
- Search Box: per session or per request. The page shows both an "introductory preview" and a "standard" price without saying which
  applies when (500 or 2,500 sessions free, then $3.00 or $11.50 per 1,000).

The price would be small at EZ/PZ's scale. The terms are the problem. Whether planning on the ground is "in-flight navigation" is a
question for Mapbox, but the display and storage rules stand on their own.

### 5.2 A self-hosted OpenStreetMap geocoder: possible, heavy

Run the geocoder on the VM, so nothing leaves our server. The two candidates:

- **Photon** (komoot/photon; Java 21 and an embedded OpenSearch; Apache-2.0):
  - GraphHopper publishes a ready US database every week: **10.6 GB** compressed (measured, `download1.graphhopper.com`, dated 2026-11-04).
  - Unpacked, it needs about 25–35 GB of disk, and about **8–16 GB of RAM** (estimate; Photon's own README asks 64 GB for the whole planet).
  - An update is a new dump swapped in, so the disk is twice that while both exist.
  - It searches as you type, which suits the field.
  - Its addresses are OpenStreetMap's own. US coverage of house numbers in OSM is patchy outside cities.
- **Nominatim** (5.3.3):
  - The US OSM extract is **12.25 GB** (measured, Geofabrik, 2026-11-04).
  - An import needs about 150–250 GB of disk, 32 GB of RAM and 12–24 hours (estimate). TIGER house-number ranges add about 10 GB (read).
  - It is a geocoder for whole addresses, not a type-ahead search.
- **Pelias** is the only one that takes OpenAddresses directly (US: about 31.8 GB, measured). It needs 100+ GB of disk and 16–32 GB of RAM
  (estimate), and the most upkeep.

**What it costs EZ/PZ:**
- **The VM.** Photon's 8–16 GB of RAM would sit beside the API's own (SAM alone holds about 1 GB), the LiDAR build service, and Gotenberg
  and the live service once deployed. The VM's RAM and free disk are the owner's to check (§12, Q3).
- **Upkeep.** Another Coolify app and another weekly update.
- **The licence.**
  - ODbL needs "© OpenStreetMap contributors" shown wherever a result is.
  - Its share-alike applies to a *derivative database*. One LZ saved from one result is likely an insubstantial extract, but that is
    for the OSMF's geocoding guideline to settle, not this plan.
- **Android has nothing offline.** A 10 GB index is not for a phone.

### 5.3 Overture Maps places, in our own index: later, if crews ask

Overture Maps Foundation publishes about 88.4 million places worldwide as GeoParquet: businesses, schools, churches, hospitals, stadiums,
towers (read, docs.overturemaps.org). The latest release is 2026-10-21.0, 11.9 GB for the world's places (measured).
- **Licence:** CDLA-Permissive-2.0, with Foursquare's share under Apache-2.0 and AllThePlaces' under CC0. The places theme holds no OSM
  data, so ODbL does not apply.
- **Each place has a `confidence`** from 0 to 1, which a build can filter on.
- **US rows: not measured** (it needs a DuckDB query). The estimate is 15–25 million.
- **The fit.** A filtered subset could join the same index as another source, so the default stays private. "Filtered" means kinds a crew
  sees from the air (schools, churches, stadiums, towers, hospitals, prisons, power plants, water towers) at a high confidence: perhaps
  1–3 million rows and 200–400 MB (estimate).
  - It would be searched on the server.
  - On Android only if the owner accepts the size (§12, Q2).
- **It holds no street addresses.** Overture's separate addresses theme has mixed licences and was not measured.

### 5.4 The recommendation

**Leave addresses out** (§12, Q2):
- Crews plan to grids, airfields, terrain and towns, which NASR and GNIS cover.
- A street address mostly matters for a hospital or a landing near a building. The FAA's 2,778 hospital heliports, and the map's own
  imagery, cover most of that.
- If crews then ask for landmarks, add Overture's places as a third source (§11, P5).
- Only if street addresses are still wanted, and the VM has the room, consider Photon (§5.2).

---


## 6. Searching on the server (the web)

The web holds no index of its own: it asks the API.

**The route.** `POST /api/places/search`:
- It needs a JWT and passes the affiliation gate, as every `/api` route does.
- It is behind a new entitlement, `place_search`, in `DEFAULT_OFF` so that testers try it first (AGENTS.md §4; §12, Q7).

```
request:  { "q": "brasstown", "near": { "lat": 34.8, "lon": -83.9 }, "limit": 8 }
response: { "available": true, "build": "2026-11-26.1",
            "places": [ { "id": "gnis:354839", "source": "gnis", "kind": "summit", "kindWords": "Summit",
                          "name": "Brasstown Bald", "ident": null, "city": null, "county": "Towns", "state": "GA",
                          "lat": 34.8741923, "lon": -83.8106479, "elevationFt": null } ] }
```

What it keeps true:
- **POST, not GET.** The text is in the body, never in a URL, so nothing in gunicorn's, Coolify's or Cloudflare's request logs or
  analytics names a place. The LiDAR routes keep coordinates out of URLs for the same reason (AGENTS.md §6).
- **`Cache-Control: no-store`, and nothing kept.** No log line with the text, no table of searches, no metrics by text.
- **`near` only orders the answer.** The client rounds it to 0.1° (about 10 km) before sending. That is enough to rank by distance, and
  it says less about where the map is.
- **The grid and the distance are the client's** (§1). The answer carries the source's lat/long only.
- **It is quick.** Answers come from the read-only SQLite file (§4.3), opened as the chart archives are: `mode=ro&immutable=1`, one
  registry of connections, a lock per connection (docs/VFR_CHARTS.md §7). It does not touch Postgres. The work is an FTS lookup and a
  sort of a few hundred candidates at most.
- **With no index on the server** (none built yet, or the Fly backend) it answers `{ "available": false, "places": [] }`, and the field
  lists coordinates only, as today.
- **A rate limit** of 20 searches a second per account, through `auth_rate_limit.check_rate_limits`. It is in-process, which holds with
  the one worker (AGENTS.md §9). A person typing never reaches it.

## 7. Searching on the device (Android)

Android carries the index and searches it with the platform's SQLite. **The text typed never leaves the device.**

**The file is downloaded, not bundled in the app.**
- The app stays its size, the index follows its own cycle (§10), and a crew that does not want it does not carry it.
- `GET /places/<build>/places.sqlite.gz` and `GET /places/<build>/manifest.json` (its size and SHA-256). Both are public and need no
  sign-in, as the chart tiles do (docs/VFR_CHARTS.md §7): the file is public data, and a download names no place.
- The download resumes with `Range`, is checked against the manifest, is unzipped beside the old file, and is then swapped in whole.
- **How big:** about 35 MB to download and 110 MB on the device (§4.3; estimate for NASR's share).
- **When** (§12, Q5): the recommendation is to fetch it by itself on an unmetered connection once signed in, and again at each new build.
  On a metered connection it waits, and the search offers *Download now (35 MB)*.
- **Where:** `noBackupFilesDir`. `allowBackup=false` already keeps the app's files off consumer clouds, and this file holds nothing
  private anyway.
- **It outlives sign-out.** It is the same public file for every account.

**How it is read.**
- **SQLite FTS4**, as §4.3 says. Opened read-only with `SQLiteDatabase.openDatabase(…, OPEN_READONLY or NO_LOCALIZED_COLLATORS)`, not
  through Room: Room would want to own and migrate the schema of a file the server made.
- A file that will not open, or whose `meta` names a schema this version does not read, is set aside and fetched again, and the search
  says so in words. It never ends the app (AGENTS.md §14 has the reasons to be careful with platform calls).
- The ranking is a Kotlin port of the server's function, held to the same fixture (§11).

**Before the file is on the device**, the search lists coordinates as today, with one line under them: "Place names are not on this
device yet" and *Download*. It does **not** ask the server instead, unless the person turns on *Search online when it is not on this
device*, which is off by default (§12, Q10).

## 8. The optional map layer

The base maps are raster tiles, so none of their labels can be clicked or searched. A layer of the index's own points makes them
clickable.

- **Switched from the Layers control**, under the MGRS grid's switch, as *Places*, with a choice of what:
  - *Airports and heliports*;
  - *Navaids and fixes*;
  - *Summits, ridges and gaps*;
  - *Towns*.

  Off until turned on. Remembered per browser (`ezpz.placesLayer`) and per device, never saved with a plan, as the MGRS grid is.
- **What a point is:** a small symbol by kind (a heliport's H, an airport's circle, a summit's triangle), with its name from zoom 11. A
  click or tap shows the same row as the search: name, kind, grid, *Set as target*, *Share*.
- **Clustered when zoomed out.**
  - **Android:** MapLibre's own GeoJSON clustering, as local points use (`PointOverlay`), from the device's index within the view, read
    off the main thread when the camera settles. Nothing is asked of the server.
  - **Web:** the server clusters. `POST /api/places/in` takes the view's box and zoom, and answers at most 500 points or clusters (a grid
    of cells over the box, counted with one `GROUP BY`). They are drawn with Leaflet's own `circleMarker` and `divIcon`.
  - No `leaflet.markercluster` or vector-tile plugin: either would be a new dependency (§12, Q6 asks whether the owner prefers one).
  - The box, like a search's text, is in the body, rounded outward to whole tenths of a degree, and not logged.
- **Screen only.** Left off LZ card exports (`feature/export/captureFilter.js`) and the brief's figures, as the MGRS grid is.
- **Not an aeronautical chart.** It is a finder. Airspace and runways drawn as vectors are docs/VFR_CHARTS.md §11's.

## 9. OPSEC: what leaves where

Searches name places crews may fly to. By default a search's text goes **nowhere outside EZ/PZ's own server, and on Android nowhere at
all**. No source in this plan sends anything to a third party.

| Action | What leaves the device | To whom | Kept |
|---|---|---|---|
| Web: a name search | The text, and the map's middle to 0.1° | Our API, through Cloudflare, which terminates TLS as it does for every API call | Nothing: no log line, no table |
| Web: the places layer | The view's box to 0.1°, and its zoom | Our API | Nothing |
| Android: a name search | **Nothing** | — | — |
| Android: the places layer | **Nothing** | — | — |
| Android: downloading the index | That this device fetched the public file | Our API | The server's ordinary request counts, with no account |
| Android: *Search online when it is not on this device* (off by default) | As the web's search | Our API | Nothing |
| Overture places, if added (§5.3) | As NASR and GNIS | As NASR and GNIS | Nothing |
| Photon, if chosen (§5.2) | As the web's search | Our API, then the geocoder on the same VM | Nothing, if its own request log is turned off |
| Mapbox (ruled out, §5.1) | The text and a proximity | Our API, **then Mapbox** | By Mapbox, under its own policy |

Rules the build keeps, each with a test:
- no search text, box or `near` in a URL, a log line, an error report or the admin's pages;
- an exception while searching logs its type, never the request;
- nothing is sent to a third party, and nothing from the device itself but the download.

## 10. Updates and the Data tab

The index is built and refreshed as a **Data tab job**, as docs/VFR_CHARTS.md §5 does charts: `data_admin/jobs.py`'s runner, the
`data_job` table, and the volumes panel's mount and reserve rules.

**A *Places* section** on `/admin/data` shows:
- the build serving now: its NASR cycle and GNIS date, its rows by kind, its size;
- the builds kept, with *Build now*, *Switch* and *Roll back*;
- a layer of the index's coverage on the tab's map, as counts by state, never points (the rule the LiDAR cache's 0.1° cells keep, D16).

**A build** (`product` `places`):
1. downloads NASR's CSV zip for a cycle (the cycle and URL from the APRA API) and GNIS's national file;
2. checks each: the columns as expected, rows within 5% of the last build's, and every coordinate inside the US and its territories;
3. writes `places-<build>.sqlite` in a staging folder under `PLACES_DIR`, and gzips it with its manifest;
4. registers it in `state.json`, as a chart build is.

A check that fails keeps the last build serving.

**Where it runs.** Parsing 147 MB of text and indexing about a million rows takes about a minute (estimate; the FTS step alone took 4 s
on the Mac). That would hold the API's interpreter lock against analyses for that minute.
- So it runs as a chart build does (docs/VFR_CHARTS.md §5.1): a fresh interpreter the job starts, at low priority, paused while an
  analysis runs.
- **It reuses that machinery, so it is built after the charts' C1** (§12, Q11).

**Automatic on production**, as the charts are (§12, Q9):
- NASR: a build each cycle, once the APRA API's `edition=next` names one. It is switched to on the cycle's effective date.
- GNIS: a build when its file's `Last-Modified` changes, looked at daily.
- One Data tab job at a time, as for every job.

**Hosts allowed** (`https`, no redirects): `external-api.faa.gov` and `nfdc.faa.gov` (NASR), `prd-tnm.s3.amazonaws.com` (GNIS). Sizes are
read by a ranged `GET`, since `nfdc.faa.gov` answers a `HEAD` with 503 (measured).

**Disk.** `PLACES_DIR` (e.g. `/data/places`), mounted read-write on production and read-only on staging, with two builds kept: about
250 MB in all (estimate).

## 11. Steps

Each is its own PR off `develop`, with AGENTS.md updated in it.

| Step | What | Done when |
|---|---|---|
| **P0** | The index builder as a script (`backend/places/build.py`): NASR and GNIS in, one FTS4 SQLite out. The ranking function in Python. `contracts/fixtures/places/`: a small index made from a slice of each source, and queries with their answers in order. | The fixture's answers are right by hand for 30 queries: identifiers, names, cities, accents, the *Mount Pleasant* case, closed pads, distance ties. The real index's size measured. |
| **P1** | The Data tab job, `state.json` and the automatic cycle; `PLACES_DIR` in the volumes panel; the public file routes. | A build made from the tab locally, refused on the container disk and below the reserve; a failed check keeps the old build serving. |
| **P2** | The web: `POST /api/places/search` in `contracts/openapi.yaml`; place rows in `TargetField`; choosing flies and offers *Set as target*; sharing with the name. | The field's tests, and the route held to the fixture. No search text in any log: a test reads the captured logs. |
| **P3** | Android: the download and its swap, the FTS4 reader, the ranking port held to the fixture, the rows in `PlaceList`. | A test of the reader over the fixture's file under Robolectric; the download's resume and checksum tests. |
| **P4** | The places layer, on the web and Android (§8). | Clusters at zoom 6, points at 12, a tap's row; screen only. |
| **P5** | Overture places, **only if the owner chooses** (§5.3). | — |

AGENTS.md changes as each step lands:
- §4: a *Place search* row, and the Data tab's entry, which gains places;
- §6: the search and layer routes, and the public file routes;
- §8: the FAA's NASR and USGS's GNIS as sources;
- §10: `PLACES_DIR`;
- §13: the ranking, and why GNIS has no elevations;
- §17: the index on Android.

## 12. Questions for the owner

Each has a recommendation, marked **(rec.)**.

| # | Question | Options |
|---|---|---|
| 1 | What does choosing a place do? | **Fly there and offer *Set as target* in the toast (rec.)**, as a grid link does · set the target at once, as a coordinate reading does on the web |
| 2 | Addresses and businesses? | **Leave them out for now (rec.)**; add Overture's landmarks later if crews ask (§5.3) · self-host Photon for street addresses (§5.2) · Mapbox is ruled out by its terms (§5.1) |
| 3 | Only if Photon is wanted: how much RAM and free disk does the VM have? | It needs about 8–16 GB of RAM and 50–70 GB of disk while updating (estimate) |
| 4 | Which GNIS classes? | **All but *Civil* and *Census* (rec.)**: about 894,000 · terrain, water and populated places only · all 43 |
| 5 | When does Android fetch the index (about 35 MB)? | **By itself on an unmetered connection, asked on a metered one (rec.)** · only when the person asks · NASR by itself, GNIS when asked |
| 6 | The map layer (§8)? | **Build it after the search, with the server clustering for the web and no new library (rec.)** · use `leaflet.markercluster` (a new dependency) · leave the layer out |
| 7 | Testers first? | **Yes, behind `place_search` in `DEFAULT_OFF` (rec.)** · on for everyone at once |
| 8 | Aliases and misspellings (*Neels Gap* for GNIS's *Neel Gap*)? | **Not now (rec.)**: exact words and prefixes only · a list of aliases kept by hand · fuzzy matching (on Android it needs SQLite's `spellfix1`, which the platform does not have) |
| 9 | Builds automatic on production, as the charts? | **Yes (rec.)** · by hand from the tab |
| 10 | May Android ask the server when the index is not on the device? | **Only if the person turns it on; off by default (rec.)** · on by default |
| 11 | Order against the VFR charts | **After the charts' C1, reusing its build process (rec.)** · before it, with a simpler process of its own |

## 13. What cannot be verified in an agent session

- **How well it finds what crews type.** Only crews can say, which is why P2 goes to testers first.
- **Android's FTS4** on the Galaxy Tab Active5 and the phones in use, and how long a query takes there. Robolectric's SQLite is not the
  device's.
- **The download** over a real cellular link.
- **The VM:** the build's time and memory there. The figures here are from a Mac.
- **Overture's US count** and the size of a filtered subset (§5.3), and **Photon's** real RAM for the US (§5.2): estimates.
