import { useEffect, useRef } from "react";
import api from "../auth/api";
import { useToast } from "../ui/Toast";
import { APP_TOO_OLD, SERVER_TOO_OLD, servedMajors, standingWith } from "./apiStanding";

// Asked again when the tab comes back after this long: a page left open for days outlives a deploy.
const LOOK_AGAIN_MS = 15 * 60 * 1000;
// Said until it is dealt with: a page that cannot talk to the server should not go quiet after five seconds.
const STAYS_MS = 24 * 60 * 60 * 1000;

const NOTICE = {
  [APP_TOO_OLD]: {
    message: "This page is older than the server, and some things will not work. Reload to get the latest version.",
    tone: "warn",
    action: { label: "Reload", onClick: () => window.location.reload() },
  },
  [SERVER_TOO_OLD]: {
    message: "The server is older than this version of EZ/PZ, and some things will not work until it is updated.",
    tone: "warn",
  },
};

/**
 * Looks at the server's API (GET /api/config) when the app opens, and again when the tab comes back to
 * the front after a while, and says so once if this build and the server have no API major in common.
 * A failed look says nothing: the calls themselves say what is wrong.
 */
export default function useApiCompat() {
  const { show } = useToast();
  const said = useRef(null);
  const lookedAt = useRef(0);

  useEffect(() => {
    let alive = true;
    const look = async () => {
      lookedAt.current = Date.now();
      try {
        const { data } = await api.get("/config");
        if (!alive) return;
        const standing = standingWith(servedMajors(data));
        if (NOTICE[standing] && said.current !== standing) {
          said.current = standing;
          show({ ...NOTICE[standing], duration: STAYS_MS });
        }
      } catch {
        // Offline, or a server down: nothing to say about versions.
      }
    };
    const onVisible = () => {
      if (document.visibilityState === "visible" && Date.now() - lookedAt.current > LOOK_AGAIN_MS) look();
    };
    look();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      alive = false;
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [show]);
}
