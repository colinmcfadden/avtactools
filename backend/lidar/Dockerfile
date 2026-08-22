# Toolchain for turning 3DEP LiDAR into Cesium 3D Tiles.
#
# Separate from the app image on purpose: PDAL and its PROJ/GDAL stack are large
# and are only needed when generating tiles, not when serving them. The app
# invokes this as a job.
#
# The PROJ grids are baked in rather than fetched at runtime. Without them PROJ
# does not fail — it returns NAVD88 orthometric heights unchanged, putting every
# point about 100 ft off. lidar/crs.py catches that, but a build that depends on
# reaching a CDN is a build that breaks when the CDN is unreachable.
FROM pdal/pdal:latest

# The base image carries two conda environments. `pdal`, PROJ and its data all
# live in envs/pdal, while bare `python3`/`pip` resolve to the base env — so a
# plain `pip install` lands beside a *different* PROJ than the one PDAL uses,
# and the datum guard would then verify grids that PDAL never reads. Putting
# envs/pdal first makes one environment authoritative for both.
ENV PATH=/opt/conda/envs/pdal/bin:$PATH \
    PROJ_NETWORK=OFF

# py3dtiles converts the reprojected point cloud into the tileset Cesium reads.
# pyproj is explicit: PDAL's PROJ is a C library, and crs.py needs the Python
# bindings to verify the vertical transform before tiles are generated.
RUN pip install --no-cache-dir "py3dtiles[las]==12.1.1" "pyproj>=3.6"

# CONUS geoid grids. Bounded rather than a full sync — the complete set is
# gigabytes and this app only ever works over the continental US.
#
# --target-dir is load-bearing. Left to its default, projsync writes to the
# user-writable dir, which pyproj reads and PDAL's PROJ does not. That split was
# observed directly: with grids in the default location, an offline PDAL run
# produced heights 30 m from the online run while the pyproj-based guard
# reported success. Both must read the same directory, and $PROJ_DATA is the one
# PDAL uses.
RUN projsync --bbox -125,24,-66,50 --target-dir "$PROJ_DATA"

WORKDIR /work

# Fail the build if the vertical transform is a no-op, so a broken image is
# caught here rather than by silently mislocated obstructions months later.
# The sample point is the validated tile near Ellijay: separation is -30.35 m.
COPY crs.py /opt/lidar/crs.py
RUN python3 -c "\
import sys; sys.path.insert(0, '/opt/lidar');\
from crs import assert_vertical_datum_applied, compound_crs;\
sep = assert_vertical_datum_applied(compound_crs('EPSG:6350'), (1077500.0, 1348500.0, 532.2));\
print(f'geoid grids OK: separation {sep:.2f} m');\
"
