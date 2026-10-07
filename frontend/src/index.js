import React from 'react';
import ReactDOM from 'react-dom/client';
import { GoogleOAuthProvider } from '@react-oauth/google';
import './index.css';
import App from './App';
import { AuthProvider } from './feature/auth/AuthContext';
import AuthGate from './feature/auth/AuthGate';
import { ToastProvider } from './feature/ui/Toast';
import { captureInviteLink } from './feature/missionPacks/inviteLink';
import reportWebVitals from './reportWebVitals';
import Viewer3DDemo from './feature/viewer3d/Viewer3DDemo';

// Development-only route for the 3D point cloud view, reached with `?view3d`.
// Guarded on NODE_ENV so a production build cannot render it and cannot use it
// to sidestep AuthGate; CRA replaces this at build time, so the branch is
// removed from the production bundle entirely.
const showViewer3DDemo =
  process.env.NODE_ENV === 'development' &&
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
    <GoogleOAuthProvider clientId={process.env.REACT_APP_GOOGLE_CLIENT_ID}>
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

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
