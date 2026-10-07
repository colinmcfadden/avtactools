import { useCallback, useEffect, useRef, useState } from "react";
import { acceptInviteLink, failureOf, packErrorMessage } from "./packApi";
import { clearInviteLink, pendingInviteLink } from "./inviteLink";

/*
 * Accepts the invitation link this tab was opened with (inviteLink.js keeps it) once the
 * person can open packs. Mounted inside AuthGate, so they are signed in and past the .mil
 * gate; `enabled` is whether their account has Mission Packs.
 *
 * `status`: "none" (no link), "unavailable" (a link, but packs are not on for this account:
 * it is kept in case they are turned on), "accepting", "joined" (`joined` is the answer:
 * { pack } or { team }), or "failed" (`message` says why; `retry` tries again when the
 * failure may pass, such as no connection).
 */

// One request per link even when React runs an effect twice (StrictMode): a second accept of
// the same link would be refused as already used.
const inFlight = new Map();

const acceptOnce = (token, accept) => {
  if (!inFlight.has(token)) {
    inFlight.set(token, accept(token).finally(() => inFlight.delete(token)));
  }
  return inFlight.get(token);
};

const roleWords = (role) => (role === "viewer" ? "a viewer" : role === "admin" ? "an admin" : role === "owner" ? "the owner" : role === "member" ? "a member" : "an editor");

/** What to tell the person once they are in. */
export const joinedMessage = (answer) => {
  if (answer?.pack) return `You joined ${answer.pack.name} as ${roleWords(answer.pack.role)}.`;
  if (answer?.team) return `You joined the team ${answer.team.name}.`;
  return "You accepted the invitation.";
};

const initial = (enabled) => {
  if (!pendingInviteLink()) return { status: "none", message: null, joined: null, retryable: false };
  return enabled
    ? { status: "accepting", message: null, joined: null, retryable: false }
    : { status: "unavailable", message: "Mission Packs are not turned on for your account yet, so the invitation is waiting.", joined: null, retryable: false };
};

export const useInviteLink = ({ enabled, accept = acceptInviteLink, onJoined } = {}) => {
  const [state, setState] = useState(() => initial(enabled));
  const [attempt, setAttempt] = useState(0);
  // Callers may pass new functions every render; only `enabled` and a retry may start a request.
  const acceptRef = useRef(accept);
  const onJoinedRef = useRef(onJoined);
  acceptRef.current = accept;
  onJoinedRef.current = onJoined;

  useEffect(() => {
    const token = pendingInviteLink();
    if (!token) return undefined;
    if (!enabled) {
      setState(initial(false));
      return undefined;
    }
    let current = true;
    setState({ status: "accepting", message: null, joined: null, retryable: false });
    acceptOnce(token, acceptRef.current).then(
      (answer) => {
        clearInviteLink();
        if (!current) return;
        setState({ status: "joined", message: joinedMessage(answer), joined: answer, retryable: false });
        onJoinedRef.current?.(answer);
      },
      (error) => {
        const failure = failureOf(error);
        // No answer, a busy or broken server: the link may still be good, so it is kept for a retry.
        const retryable = failure.status === 0 || failure.status === 429 || failure.status >= 500;
        if (!retryable) clearInviteLink();
        if (!current) return;
        setState({ status: "failed", message: packErrorMessage(failure), joined: null, retryable });
      },
    );
    return () => {
      current = false;
    };
  }, [enabled, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  const dismiss = useCallback(() => setState({ status: "none", message: null, joined: null, retryable: false }), []);
  return { ...state, retry, dismiss };
};
