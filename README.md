# LZ Card Caddy 🚁

> **Current Status:** v1.0.0-ALPHA.1 (Public Beta)

**LZ Card Caddy** is a modern geospatial intelligence tool designed for aviation mission planning. It combines interactive mapping with AI-powered terrain analysis to help crews identify, analyze, and mark Helicopter Landing Zones (HLZs) and Pickup Zones (PZs) with precision.

It leverages a responsive React frontend for the mission interface and a Python/Flask backend for heavy-duty geospatial processing and AI model inference.

## ✨ Features

* **📍 Precision Targeting:** Rapidly center the map using MGRS (Military Grid Reference System) coordinates.
* **🧠 AI Terrain Analysis:** Powered by the **Segment Anything Model (SAM)** to automatically detect LZs and potential obstacles.
* **📉 Slope Analysis:** Instant visual overlays to identify safe landing gradients versus hazardous terrain.
* **🎨 Tactical Graphics:**
    * **LZ Box:** Auto-generated safety box visualization.
    * **Interactive Markers:** Drag-and-drop tools for Helo, PZ, Sector, Unit icons and go around markers.
* **📤 Export Capabilities:** Generate high-resolution, mission-ready "LZ Cards" (images).






# 🚀 Getting Started
## Prerequisites
* Node.js (v16+)
* Python (v3.11+)
* Docker (optional, for testing backend container)
* A Mapbox API Key (optional, if using Mapbox tiles)

## Installation (Frontend)
The frontend handles the UI and map interactions.
```
# Navigate to the frontend directory
cd frontend

# Install dependencies
npm install

# Set up Environment Variables
# Create a .env file and add your backend URL
echo "REACT_APP_API_URL=http://localhost:7860" > .env

# Start the development server
npm start
```

The app should now be running at http://localhost:3000

## Installation (Backend)
The backend handles the AI model and image processing.

Note: You will need the sam_b.pt model weights file (approx 350MB). Place it in the root backend/ directory.

Bash
```
# Navigate to the root/backend directory
cd backend

# Create a virtual environment (Recommended)
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies (Requires system libs: libgl1)
pip install -r requirements.txt

# Run the Flask API
python src/app.py
```

The API should now be running at http://localhost:7860

# 📦 Deployment

## Frontend (Vercel)
1. Push your code to GitHub.
2. Import the project into Vercel.
3. Crucial: Set the Environment Variable in Vercel Settings:
    - REACT_APP_API_URL: https://your-huggingface-space-url.hf.space (No trailing slash, no port).
4. Deploy.

## Backend (Hugging Face Spaces)
The backend requires a Docker container due to system dependencies (OpenCV) and the large AI model.

1. Create a new Space on Hugging Face (SDK: Docker).
2. Upload sam_b.pt via the Files tab (Git LFS).
3. Ensure your Dockerfile and README.md metadata are configured correctly (see Dockerfile in repo).
4. The Space will build and provide a URL. Use this URL in your Frontend config.

# 📂 Project Structure
```
lz-card-caddy/
├── frontend/                # React Application
│   ├── public/              # Static assets
│   ├── src/
│   │   ├── components/      # UI Components (Sidebar, MapTools)
│   │   ├── App.js           # Main Entry
│   │   └── App.css          # Styles
│   └── package.json
│
├── backend/                 # Python API
│   ├── src/
│   │   ├── routes/          # API Routes (Terrain, Export)
│   │   └── app.py           # Flask Entry Point
│   ├── sam_b.pt             # AI Model Weights (Git LFS)
│   ├── Dockerfile           # HF Spaces Config
│   └── requirements.txt     # Python Dependencies
│
└── README.md                # You are here
```