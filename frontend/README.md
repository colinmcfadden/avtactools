# EZ/PZ web app

The React single-page app, built with [Vite](https://vite.dev) and tested with [Vitest](https://vitest.dev).
Project-wide context (architecture, rules, deployment) is in the repository's `AGENTS.md`.

## First run

Copy `.env.example` to `.env` (it points at a local backend on `http://127.0.0.1:5000/api`), then:

```powershell
npm install
npm start
```

Open `http://localhost:3000` (not `127.0.0.1`: the backend's CORS default names `localhost`).

## Scripts

| Command | What it does |
|---|---|
| `npm start` / `npm run dev` | Development server on port 3000. Copies Cesium into `public/cesium` first. |
| `npm test` | Runs every test once (`vitest run`). |
| `npm run test:watch` | Runs the tests and re-runs them as files change. |
| `npm run build` | Production bundle into `dist/`. Copies Cesium first. |
| `npm run preview` | Serves `dist/`, to check a production build (the 3D view must be checked this way). |

Environment variables are read at build time, and only names starting `VITE_` reach the app.
