import React, { useCallback, useState } from "react";
import Viewer3D from "./Viewer3D";

// The tileset generated in Stage 2 from USGS_LPC_GA_Statewide_2018_B18_DRRA,
// a 500 m box in the Chattahoochee National Forest. Served statically from
// public/ for now; Stage 4 generates these per area of interest.
const DEMO_TILESET = "/tiles/lz_demo/tileset.json";

/**
 * Stage 3: prove one tileset renders, with nothing else wired in.
 *
 * Deliberately standalone. The workspace, routes and LZ diagrams come later —
 * the question this answers is whether the tiles built in Stage 2 draw
 * correctly and perform acceptably.
 */
const Viewer3DDemo = () => {
  const [info, setInfo] = useState(null);

  const handleReady = useCallback(({ tileset }) => {
    // geometricError lives on the root tile, not the tileset — reading it off
    // the tileset yields NaN.
    setInfo({
      radius: Math.round(tileset.boundingSphere.radius),
      geometricError: Math.round(tileset.root?.geometricError ?? 0),
    });
  }, []);

  return (
    <div style={{ position: "fixed", inset: 0 }}>
      <Viewer3D tilesetUrl={DEMO_TILESET} onReady={handleReady} />
      {info && (
        <div className="viewer3d__status" style={{ top: "auto", bottom: 12 }}>
          <strong>Tileset</strong>
          radius {info.radius} m · geometric error {info.geometricError}
        </div>
      )}
    </div>
  );
};

export default Viewer3DDemo;
