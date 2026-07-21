# EZ/PZ 🚁

> **Current Status:** v1.0.0-ALPHA.1 (Public Beta)

**EZ/PZ** is a modern geospatial intelligence tool designed for aviation mission planning. It combines interactive mapping with AI-powered terrain analysis to help crews identify, analyze, and mark Helicopter Landing Zones (HLZs) and Pickup Zones (PZs) with precision.

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

### Project Structure
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

## Installation (Frontend)
The frontend handles the UI and map interactions.
```
# Navigate to the frontend directory
cd frontend

# Install dependencies
npm install

# Set up Environment Variables
# Create a .env file and add your backend URL
echo "REACT_APP_API_URL=http://127.0.0.1:5000/api" > .env

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
python app.py
```

The API should now be running at http://localhost:5000

Account registration, transactional email, local development, and Fly/Vercel
security configuration are documented in [AUTHENTICATION.md](AUTHENTICATION.md).

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

## 🚀 Development & Release Workflow

This repository uses a modified Gitflow branching strategy paired with Automated Semantic Versioning. By following strict branch naming and commit message conventions, our releases, changelogs, and version numbers (for both the React frontend and Python backend) are 100% automated.

### 🌿 1. Branching Strategy

We maintain two primary branches:

- ```main```: The production-ready state of the app. Never commit directly to ```main```.

- ```develop```: The active integration branch. All feature branches merge here first.

#### Temporary branches:

- ```feature/your-feature-name```: Branched from develop. Used for new work.

- ```release/vX.X.X```: Branched from develop. Used to prep a batch of features for production.

- ```hotfix/issue-description```: Branched directly from main. Used only for emergency production fixes.

💬 2. Commit Message Conventions
Our automated release bot reads your commit messages to determine if it should bump the version number, and by how much. You must prefix your commits using the Conventional Commits standard:

- ```fix```:  (Patch Release: ```v1.0.1```) -> Used for bug fixes.
    - Example: ```fix: resolve mobile layout overlapping issue```

- ```feat```:  (Minor Release: v1.1.0) -> Used for new, backwards-compatible features.
    - Example: ```feat: add user authentication dashboard```

- ```feat!:```  or ```fix!:```  (Major Release: ```v2.0.0```) -> The ```!``` denotes a BREAKING CHANGE.
    - Example: ```feat!: migrate from REST to GraphQL API```

- Other prefixes (```chore```:, ```docs```:, ```refactor:```, ```style:```) do not trigger a version bump, but keep the history clean.

### 🛠️ 3. The Step-by-Step Developer Lifecycle

***Phase 1: Building a Feature or Fix***
1. Ensure your local develop branch is up to date.
2. Create a new branch: ```git checkout -b feature/new-login-page```
3. Write your code (Frontend, Backend, or both).
4. Commit your work using the correct prefix: ```git commit -m "feat: add new login page UI"```
5. Push your branch: ```git push origin feature/new-login-page```

***Phase 2: Merging to Develop***
1. Open a Pull Request (PR) in GitHub from ```feature/new-login-page``` into ```develop```.
2. Vercel will automatically generate a Preview Deployment for this PR.
3. Once reviewed and tested, squash/merge the PR into ```develop```.
4. (No official version numbers change during this phase).

***Phase 3: Preparing a Release***
When ```develop``` has enough features/fixes to go to production:

1. Cut a release branch from ```develop```: ```git checkout -b release/next-version```
2. Open a Pull Request from ```release/next-version``` into ```main```.

***Phase 4: Production & Automation***
When the PR is merged into ```main```, the automation pipeline takes over:

1. ***Vercel Build***: Vercel sees the merge to ```main``` and immediately starts building the production deployment.
2. ***GitHub Actions***: The Semantic Release bot runs in the background. It analyzes all the ```feat:``` and ```fix:``` commits since the last release.
3. ***Version Bump***: The bot automatically figures out the new version number (e.g., ```v1.2.0```).
4. ***File Updates***: The bot reaches into ```./frontend``` and updates ```package.json```. It reaches into ```./backend``` and updates ```version.py```.
5. ***Changelog***: The bot generates a beautiful ```CHANGELOG.md``` file detailing exactly what features and fixes are in this release.
6. ***The Commit Back***: The bot commits these updated files directly to ```main``` with the message ```chore(release): 1.2.0 [skip ci]```.
7. ***Skipping Double-Builds***: Vercel sees the ```[skip ci]``` flag on the bot's commit and correctly ignores it, preventing a redundant double-deployment.
8. ***GitHub Release***: The bot publishes an official GitHub Release and creates a Git tag (```v1.2.0```).
