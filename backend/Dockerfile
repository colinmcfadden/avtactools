# syntax=docker/dockerfile:1

ARG PYTHON_VERSION=3.11.9

FROM python:${PYTHON_VERSION}-slim

LABEL fly_launch_runtime="flask"

WORKDIR /code

# System libraries required by opencv (pulled in by ultralytics) at runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Install CPU-only torch first: the default PyPI wheel bundles multi-GB CUDA
# libraries that are useless on Fly's CPU machines and can break the build.
# Its own layer, ahead of requirements.txt, so changing any other dependency
# does not reinstall it — that reinstall is most of a slow deploy.
RUN pip3 install --no-cache-dir torch==2.10.0 torchvision==0.25.0 \
        --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt requirements.txt
RUN pip3 install --no-cache-dir -r requirements.txt

# Geoid grids for the 3D terrain (terrain_tiles.py). Without them PROJ passes
# heights through unchanged and reports success, and the terrain sits ~30 m
# off the point clouds. Same CONUS set as the LiDAR build image, so both pick
# the same NAVD88 -> ellipsoid transformation and agree. The check fails the
# build rather than shipping uncorrected terrain.
#
# --target-directory is load-bearing: --system-directory downloads somewhere
# pyproj never reads, and the check below then fails at 0 m. "--bbox=" because
# argparse would read a bare -125,... as an option and refuse it.
RUN pyproj sync --bbox=-125,24,-66,50 \
        --target-directory "$(python -c 'import pyproj; print(pyproj.datadir.get_data_dir())')" \
    && python -c "from pyproj import Transformer; \
t = Transformer.from_crs('EPSG:4326+5703', 'EPSG:4979', always_xy=True); \
sep = t.transform(-84.08, 34.78, 0.0)[2]; \
assert abs(sep) > 1, f'geoid grids missing: separation {sep} m'; \
print(f'geoid grids OK: separation {sep:.2f} m')"

COPY . .

EXPOSE 8080

# One worker — SAM holds ~1 GB and the rate limiter, QR and route-share stores
# live in process memory — but several threads, so terrain tiles and point
# cloud files are served side by side rather than queueing behind each other
# or behind an analysis. SAM itself is serialised by a lock. The generous
# timeout is for CPU inference.
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--threads", "8", "--timeout", "180", "app:app"]
