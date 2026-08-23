# Running point-cloud builds on the server

The LiDAR, the generated tilesets, and the backend that serves them should all
live on the same machine. Nothing then crosses the network, and no workstation
needs a copy of a 33 GB survey.

For a self-hosted deployment that machine is the VM running Coolify. The
downloaded 3DEP tiles are already there; this is how to build against them in
place.

## Why not mount the VM's disk on a workstation

It works — NFS or SMB, then `--collection /mnt/lidar` — but every build then
pulls whole 25 MB tiles across the LAN to be cropped down to a 500 m box, and
writes the result back to a tileset store the backend cannot see anyway. The
build has to end up on the server regardless, so it may as well start there.

## One-time setup

SSH to the VM.

### 1. The repository

```sh
sudo mkdir -p /opt/avtactools && sudo chown "$USER" /opt/avtactools
git clone <your-remote> /opt/avtactools
cd /opt/avtactools
```

### 2. The toolchain image

PDAL, py3dtiles, and the PROJ geoid grids, all pinned in the Dockerfile. The
grids matter: without them heights pass through unreprojected and the surface
sits about 30 m off in Georgia.

```sh
docker build -t avtac-lidar:dev backend/lidar
```

Expect several minutes and roughly 5 GB. The build asserts the geoid transform
works before it finishes, so a successful build means the datum is correct.

### 3. Where things live

| path | holds | who reads it |
|---|---|---|
| `/data/lidar` | downloaded LAZ tiles | the build |
| `/data/tiles` | generated tilesets | the backend |
| `/data/topo` | DEMs | the backend, for terrain |

```sh
sudo mkdir -p /data/tiles && sudo chown "$USER" /data/tiles
```

### 4. Point the backend at them

In Coolify, on the backend application:

* **Storages** — mount `/data/tiles` and `/data/topo` into the container at the
  same paths.
* **Environment** —

  ```
  LIDAR_TILES_DIR=/data/tiles
  TERRAIN_DATA_DIR=/data/topo
  ```

Redeploy. `GET /api/lidar/tilesets` should return a JSON list, empty until the
first build.

## Building

```sh
cd /opt/avtactools
python3 tools/build_lz.py --lat 34.596407 --lon -84.128098 \
    --collection /data/lidar --tiles-dir /data/tiles
```

The first run indexes every tile header in the collection — a few thousand
files, seconds, cached afterwards in `/data/lidar/tile_index.json`. Add
`--reindex` after adding more tiles.

Nothing needs restarting. The backend serves whatever is in `/data/tiles` on
the next request, so the 3D window's **Check again** picks up a finished build.

Useful arguments:

| argument | effect |
|---|---|
| `--radius 500` | wider area, still full density up to 1 km |
| `--context 1000` | thinned landscape ring; the core keeps its detail |
| `--classes 1,2,3,4,5` | keeps unclassified returns, about 23% more, mostly canopy |
| `--grid "16S GD 63386 32036"` | MGRS instead of lat/lon |

## Telling the app where builds happen

So the "not generated" panel prints a command that works on your deployment,
set these for the frontend build (Vercel, or `.env`):

```
REACT_APP_LIDAR_BUILD_CWD=/opt/avtactools
REACT_APP_LIDAR_COLLECTION=/data/lidar
```

## If a workstation build is still wanted

Reading the collection over a mount is supported; it is only slower.

```powershell
net use L: \\192.168.0.121\lidar
python tools/build_lz.py --lat ... --lon ... --collection L:\
```

The resulting tileset then has to reach the server's `/data/tiles` to be
served, which is the step that makes this the long way round.

## Later: an index instead of a directory

A directory of plain LAZ has no spatial index, so a build reads whole tiles to
crop a small box out of them. Building an Entwine index over the collection
once turns it into the same thing AWS serves — a spatial query that touches
only the area of interest — and any machine on the LAN can then read it over
HTTP:

```sh
docker run --rm -v /data/lidar:/in:ro -v /data/ept:/out \
    connormanning/entwine build -i /in -o /out
```

Hours over a full survey, then `--source http://<vm>/ept/ept.json` reads it
the way `python -m lidar` already reads AWS. Worth it only once builds are
frequent enough for the per-build tile reads to be the bottleneck.
