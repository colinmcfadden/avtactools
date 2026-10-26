import React from 'react';
import ReactDOM from 'react-dom/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import './index.css';
import App from './App';
import { AuthProvider } from './feature/auth/AuthContext';
import AuthGate from './feature/auth/AuthGate';
import { ToastProvider } from './feature/ui/Toast';
import { captureInviteLink } from './feature/missionPacks/inviteLink';
import Viewer3DDemo from './feature/viewer3d/Viewer3DDemo';

// Development-only route for the 3D point cloud view, reached with `?view3d`.
// Guarded on DEV so a production build cannot render it and cannot use it
// to sidestep AuthGate; Vite replaces this at build time, so the branch is
// removed from the production bundle entirely.
const showViewer3DDemo =
  import.meta.env.DEV &&
  new URLSearchParams(window.location.search).has('view3d');

// A pack or team invitation (?invite=<token>) is taken out of the address before anything renders,
// so it is not left in the history; it is accepted once the person has signed in (useInviteLink).
captureInviteLink();

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  showViewer3DDemo ? (
    <Viewer3DDemo />
  ) : (
  <React.StrictMode>
    <GoogleOAuthProvider clientId={import.meta.env.VITE_GOOGLE_CLIENT_ID}>
      <AuthProvider>
        <AuthGate>
          <ToastProvider>
            <App />
          </ToastProvider>
        </AuthGate>
      </AuthProvider>
    </GoogleOAuthProvider>
  </React.StrictMode>
  )
);
