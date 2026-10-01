# Point clouds on the server

Opening the 3D view on an LZ with no point cloud starts a build automatically.
The build runs in a small **build service** — this directory's Docker image,
which holds PDAL, py3dtiles and the geoid grids — beside the backend. The
backend forwards the request, the browser shows progress, and the finished
tileset lands in the store the backend serves. Each LZ is built once; every
later visit opens straight away.

```
browser ──POST /api/lidar/build──► backend ──POST /builds──► build service
        ◄──GET  /api/lidar/build/<key>──      ◄──GET /builds/<key>──
                                    │                    │
                                    └──── /data/tiles ◄──┘  (shared volume)
```

The backend never runs the toolchain and never touches Docker. Without the
service configured, the 3D window falls back to printing a command for an
operator to run.

## What lives where on the VM

| path | holds | mounted into |
|---|---|---|
| `/data/lidar` | downloaded LAZ tiles | build service |
| `/data/tiles` | generated tilesets | build service (writes), backend (reads) |
| `/data/cache` | the USGS survey coverage index (~8 MB) | build service |
| `/data/topo` | DEMs | backend, for terrain |

```sh
sudo mkdir -p /data/tiles /data/cache
```

## Setup in Coolify

Make a shared secret first — the backend presents it, the service checks it:

```sh
openssl rand -hex 32
```

### 1. The build service

Add a new **Application** in the same project as the backend:

| setting | value |
|---|---|
| Source | this repository, same branch as the backend |
| Build pack | Dockerfile |
| Base directory | `/backend/lidar` |
| Domains | **none** — it must not be reachable from the internet |
| Port | `8090` |
| Storages | `/data/tiles`, `/data/lidar`, `/data/cache`, each at the same path in the container |

Environment:

```
LIDAR_BUILDER_TOKEN=<the secret>
LIDAR_COLLECTION=/data/lidar
```

The first image build takes several minutes and about 5 GB; later deploys only
rebuild the final code layer. The image refuses to finish building if the
geoid grids are not working, so a successful build means heights are correct.

### 2. The backend

Add to its environment, then redeploy:

```
LIDAR_TILES_DIR=/data/tiles
LIDAR_BUILDER_URL=http://<build service hostname>:8090
LIDAR_BUILDER_TOKEN=<the same secret>
```

Use the build service's internal container hostname, which Coolify shows on
that application. Both applications must be on the same Docker network — if
the backend cannot reach it, enable **Connect to Predefined Network** on both.

### 3. Check it

Open the 3D view on an LZ that has never been built. It should say
**Building point cloud** and step through *Finding the LiDAR survey*,
*Processing points* and *Building 3D tiles*, then open. About two minutes from
AWS; less from the local collection.

From the VM, the service answers on its health endpoint without the token:

```sh
docker exec <build service container> python3 -c \
  "import urllib.request; print(urllib.request.urlopen('http://localhost:8090/health').read())"
```

## Behaviour worth knowing

- **One build at a time.** A build saturates a core and can read hundreds of
  megabytes. Others queue — up to 8 by default (`LIDAR_BUILDER_QUEUE`) — and
  the browser shows its place in line. A full queue answers "try again
  shortly" rather than growing.
- **Asking twice is free.** Reopening the window joins the build already
  running for that place.
- **Only wanted builds run.** The 3D window polls its build every 3 s; a build
  nobody has polled for 90 s is dropped, queued or mid-run (its PDAL or
  py3dtiles process is killed). Closing the window drops it at once, and a
  refreshed page drops the previous load's builds as it starts. A saved LZ's
  build is marked `keep` and always finishes. Without this a refresh left its
  build running and every LZ opened next queued behind it.
- **Builds are staged.** Each is written under `/data/tiles/.staging-<key>` and
  moved into place only when complete, so a half-written tileset is never
  served. Staging left by a restart is cleared on startup.
- **Local tiles first, AWS otherwise.** With `LIDAR_COLLECTION` set, the service
  builds from the download when it covers at least 95% of the area, and from
  the AWS index when it doesn't — so an LZ near the edge of the download gets a
  complete point cloud rather than one with a side cut off.
- **Area is capped at a 1 km radius.** Builds are interactive. For wider
  landscape, set `LIDAR_BUILD_CONTEXT_M` (e.g. `1000`) to add a thinned ring
  around every build — at the cost of several more minutes each.
- **No coordinates in logs.** The service logs opaque tileset keys and stage
  names only.

## Local development (Windows)

Build the image, run it against the repo's tile store, and point a local
backend at it:

```powershell
docker build -t avtac-lidar:dev backend/lidar
docker run -d --name lidar-builder -p 8090:8090 -e LIDAR_BUILDER_TOKEN=local-dev-token -v C:\_dev\avtactools\data\tiles:/data/tiles -v C:\_dev\avtactools\data\cache:/data/cache avtac-lidar:dev

cd backend
$env:LIDAR_TILES_DIR="C:\_dev\avtactools\data\tiles"; $env:LIDAR_BUILDER_URL="http://127.0.0.1:8090"; $env:LIDAR_BUILDER_TOKEN="local-dev-token"; $env:TERRAIN_DATA_DIR="C:\_dev\avtactools\topo"; $env:PROJ_NETWORK="ON"; python app.py
```

`docker logs -f lidar-builder` shows builds as they run.

## Building by hand

`tools/build_lz.py` runs the same build through the same image, for
pre-building a list of LZs or for a host with no build service:

```sh
python3 tools/build_lz.py --lat 34.596407 --lon -84.128098 \
    --collection /data/lidar --tiles-dir /data/tiles
```

| argument | effect |
|---|---|
| `--radius 500` | wider area, still full density up to 1 km |
| `--context 1000` | thinned landscape ring; the core keeps its detail |
| `--classes 1,2,3,4,5` | keeps unclassified returns, about 23% more, mostly canopy |
| `--grid "16S GD 63386 32036"` | MGRS instead of lat/lon |

The first run against a collection indexes every tile header — a few thousand
files, seconds — and caches it in `/data/lidar/tile_index.json`. Add
`--reindex` after adding tiles.

On a host without the service, set these for the frontend build so the 3D
window's fallback prints a command that works there:

```
REACT_APP_LIDAR_BUILD_CWD=/opt/avtactools
REACT_APP_LIDAR_COLLECTION=/data/lidar
```

## Later: an index instead of a directory

A directory of plain LAZ has no spatial index, so a build reads whole tiles to
crop a small box out of them. Building an Entwine index over the collection
once turns it into the same thing AWS serves — a spatial query that touches
only the area of interest:

```sh
docker run --rm -v /data/lidar:/in:ro -v /data/ept:/out \
    connormanning/entwine build -i /in -o /out
```

Hours over a full survey. Worth it only once builds are frequent enough for
per-build tile reads to be the bottleneck.
