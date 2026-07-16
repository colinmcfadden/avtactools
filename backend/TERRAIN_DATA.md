# Local terrain data

The terrain-analysis API uses this source order by default:

1. local high-resolution GeoTIFF or Cloud Optimized GeoTIFF (COG), at 15 m resolution or finer;
2. local DTED Level 2 (`.dt2`);
3. the existing Terrarium service as a fallback.

Set `TERRAIN_DATA_DIR` to one or more approved, read-only directories. Separate multiple directories with the host operating system's path separator (`;` on Windows, `:` on Linux).

```text
TERRAIN_DATA_DIR=/terrain/3dep:/terrain/dted
TERRAIN_SOURCE=auto
```

For Docker, mount data at runtime rather than copying it into the image:

```text
docker run --rm -p 5000:8080 \
  -v /approved/terrain/3dep:/terrain/3dep:ro \
  -v /approved/terrain/dted:/terrain/dted:ro \
  -e TERRAIN_DATA_DIR=/terrain/3dep:/terrain/dted \
  avtactools-backend
```

Relevant settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TERRAIN_SOURCE` | `auto` | `auto`, `local`, or `terrarium` |
| `TERRAIN_LOCAL_HIGHRES_MAX_M` | `15` | Maximum resolution considered high-resolution local coverage |
| `TERRAIN_LOCAL_MIN_RESOLUTION_M` | `1` | Prevents upsampling below this local output resolution |
| `TERRAIN_MAX_GRID_SIZE` | `1024` | Caps the generated slope raster dimensions |
| `TERRAIN_ANALYSIS_PADDING_M` | `40` | DEM padding around an LZ for edge-safe 3×3 gradients |
| `TERRAIN_TERRARIUM_SLOPE_ZOOM` | `13` | Fallback tile zoom, constrained to 9–13 |

The API sends the browser a clipped PNG overlay, bounds, source name, resolution, vertical-datum metadata, and slope statistics. It does not send source terrain files to the browser.

## Data handling

- Do not commit approved NGA, DTED, 3DEP, or other source data to Git.
- Keep controlled data outside the Docker build context and mount it read-only at runtime.
- Verify source provenance, edition/date, horizontal reference, and vertical datum before operational use. Vertical-datum differences do not change slope, but they do affect displayed MSL elevations and exports.
- The terrain API retains optional support for 6° nose-high and 15° nose-low/cross-slope checks when a future aircraft-profile workflow supplies a landing heading. The current UI intentionally displays general terrain-slope bands only.
