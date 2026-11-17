import { useEffect } from "react";
import { useMap } from "react-leaflet";
import { graphicScale, referenceZoom } from "./scaleRule";
import "./graphicScale.css";

export const SCALE_VARIABLE = "--ezpz-graphic-scale";
export const FAR_CLASS = "ezpz-graphics-far";

/**
 * Sets the planning graphics' scale on the map's element as the map zooms (scaleRule.js), which the graphics read
 * through CSS (graphicScale.css). It is set as each zoom is known, in the same call: a mission overlay sets the view and
 * draws at once, so it must find the graphics as the zoom has them.
 */
const GraphicScale = ({ boundary }) => {
  const map = useMap();
  const reference = referenceZoom(boundary);

  useEffect(() => {
    const element = map.getContainer();
    const apply = (zoom) => {
      const { scale, hidden } = graphicScale(zoom, reference);
      element.style.setProperty(SCALE_VARIABLE, String(scale));
      element.classList.toggle(FAR_CLASS, hidden);
    };
    const now = () => apply(map.getZoom());
    // A zoom animation says where it is going first, so the graphics shrink with the map rather than after it.
    const animating = (event) => apply(event.zoom);
    now();
    map.on("zoom zoomend viewreset", now);
    map.on("zoomanim", animating);
    return () => {
      map.off("zoom zoomend viewreset", now);
      map.off("zoomanim", animating);
      element.style.removeProperty(SCALE_VARIABLE);
      element.classList.remove(FAR_CLASS);
    };
  }, [map, reference]);

  return null;
};

export default GraphicScale;
