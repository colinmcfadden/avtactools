import { useEffect, useState } from "react";

// Matches the CSS breakpoint (App.css @media max-width: 850px) that hides the
// desktop floating auth control and shows the in-sidebar mobile account row.
const MOBILE_QUERY = "(max-width: 850px)";

/** True when the viewport is at/below the mobile breakpoint; updates on resize. */
export const useIsMobile = () => {
  const [isMobile, setIsMobile] = useState(
    () => typeof window !== "undefined" && window.matchMedia(MOBILE_QUERY).matches,
  );

  useEffect(() => {
    const mql = window.matchMedia(MOBILE_QUERY);
    const onChange = (e) => setIsMobile(e.matches);
    mql.addEventListener("change", onChange);
    setIsMobile(mql.matches); // sync in case it changed between render and effect
    return () => mql.removeEventListener("change", onChange);
  }, []);

  return isMobile;
};
