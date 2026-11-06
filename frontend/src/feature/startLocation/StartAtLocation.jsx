import { useEffect, useRef } from "react";
import { useMap } from "react-leaflet";
import { attachStartAtLocation } from "./startLocation";

/**
 * Opens the map at the person's location when their browser allows it (startLocation.js). `claimed` is true while the app
 * has a view of its own to show (a target, a grid link, a brief presented); it is read when the ask starts and when the answer
 * comes, never as a reason to ask again. Put it first among the map's children, so it is listening before any of them moves it.
 */
const StartAtLocation = ({ claimed = false }) => {
  const map = useMap();
  const claimedRef = useRef(claimed);
  claimedRef.current = claimed;
  useEffect(() => attachStartAtLocation(map, { claimed: () => claimedRef.current }), [map]);
  return null;
};

export default StartAtLocation;
