import React, { useEffect, useRef } from "react";
import { compassPose } from "./compass";

// Below this a frame's change is invisible, so the DOM is left alone.
const CHANGE_DEG = 0.1;
const FACE_NORTH_SECONDS = 0.6;

/**
 * A compass rose for the 3D view that turns and tilts with the camera, so
 * which way is north — and so which way a pan will go — is always plain.
 * Clicking it swings the view round to face north about the point at the
 * centre of the screen.
 *
 * Follows the camera every frame (scene.postRender) but writes to the DOM
 * directly, and only when the pose changes, so it costs React nothing.
 */
const Compass3D = ({ viewer, Cesium }) => {
  const dialRef = useRef(null);
  const headingRef = useRef(null);
  const buttonRef = useRef(null);

  useEffect(() => {
    if (!viewer || viewer.isDestroyed()) return undefined;
    let last = { rotate: NaN, tilt: NaN };

    const update = () => {
      const { rotateDeg, tiltDeg, headingText } =
        compassPose(viewer.camera.heading, viewer.camera.pitch);
      if (Math.abs(rotateDeg - last.rotate) < CHANGE_DEG
          && Math.abs(tiltDeg - last.tilt) < CHANGE_DEG) return;
      last = { rotate: rotateDeg, tilt: tiltDeg };
      if (dialRef.current) {
        // Turn within the dial's plane first, then lean the plane back.
        dialRef.current.style.transform = `rotateX(${tiltDeg}deg) rotateZ(${rotateDeg}deg)`;
      }
      if (headingRef.current) headingRef.current.textContent = headingText;
      buttonRef.current?.setAttribute(
        "aria-label", `Facing ${headingText.replace("°T", " degrees true")}. Face north`);
    };

    update();
    return viewer.scene.postRender.addEventListener(update);
  }, [viewer]);

  const faceNorth = () => {
    if (!viewer || viewer.isDestroyed()) return;
    const { scene, camera } = viewer;
    const canvas = scene.canvas;
    // Turn about whatever is at the centre of the screen, so the view keeps
    // looking at the same place and only the direction changes.
    const centre = new Cesium.Cartesian2(canvas.clientWidth / 2, canvas.clientHeight / 2);
    const target = scene.globe.pick(camera.getPickRay(centre), scene);
    if (!target) {
      camera.flyTo({
        destination: camera.positionWC,
        orientation: { heading: 0, pitch: camera.pitch, roll: 0 },
        duration: FACE_NORTH_SECONDS,
      });
      return;
    }
    camera.flyToBoundingSphere(new Cesium.BoundingSphere(target, 0), {
      offset: new Cesium.HeadingPitchRange(
        0, camera.pitch, Cesium.Cartesian3.distance(camera.positionWC, target)),
      duration: FACE_NORTH_SECONDS,
    });
  };

  return (
    <button
      type="button"
      ref={buttonRef}
      className="viewer3d__compass"
      onClick={faceNorth}
      title="Face north"
    >
      <span className="viewer3d__compass-stage" aria-hidden="true">
        <svg ref={dialRef} className="viewer3d__compass-dial" viewBox="-50 -50 100 100">
          <circle r="46" className="viewer3d__compass-ring" />
          {/* Every 10°: long at the cardinal points, medium every 30°. */}
          {Array.from({ length: 36 }, (_unused, i) => (
            <line
              key={i}
              x1="0" y1="-46"
              x2="0" y2={i % 9 === 0 ? -38 : i % 3 === 0 ? -41 : -43}
              transform={`rotate(${i * 10})`}
              className="viewer3d__compass-tick"
            />
          ))}
          {/* North half of the needle red, south half pale. */}
          <path d="M0 -19 L6 0 L-6 0 Z" className="viewer3d__compass-north" />
          <path d="M0 19 L6 0 L-6 0 Z" className="viewer3d__compass-south" />
          <text y="-24" className="viewer3d__compass-n">N</text>
          <text x="29" y="4" className="viewer3d__compass-letter">E</text>
          <text y="33" className="viewer3d__compass-letter">S</text>
          <text x="-29" y="4" className="viewer3d__compass-letter">W</text>
        </svg>
      </span>
      <span ref={headingRef} className="viewer3d__compass-heading" aria-hidden="true">000°T</span>
    </button>
  );
};

export default Compass3D;
