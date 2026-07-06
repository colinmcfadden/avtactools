# syntax=docker/dockerfile:1

ARG PYTHON_VERSION=3.11.9

FROM python:${PYTHON_VERSION}-slim

LABEL fly_launch_runtime="flask"

WORKDIR /code

# System libraries required by opencv (pulled in by ultralytics) at runtime.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt requirements.txt

# Install CPU-only torch first: the default PyPI wheel bundles multi-GB CUDA
# libraries that are useless on Fly's CPU machines and can break the build.
RUN pip3 install --no-cache-dir torch==2.10.0 torchvision==0.25.0 \
        --index-url https://download.pytorch.org/whl/cpu \
    && pip3 install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8080

# Single worker (the SAM model is ~1GB resident) with a generous timeout for
# CPU terrain inference.
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--timeout", "180", "app:app"]
