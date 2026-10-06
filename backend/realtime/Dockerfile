# The mission-pack live service (docs/MISSION_PACKS.md, "The live stream").
# Built on its own, like backend/lidar: Coolify base directory /backend/realtime.
FROM python:3.11-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY service.py .

# Needs DATABASE_URL (the API's Postgres) and REALTIME_API_URL (the API's root); see service.py.
ENV PYTHONUNBUFFERED=1
EXPOSE 8091
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/health' % os.environ.get('REALTIME_PORT', '8091'), timeout=4)"
USER nobody
CMD ["python", "service.py"]
