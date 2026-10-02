import { fireEvent, render, screen } from "@testing-library/react";
import Compass3D from "./Compass3D";
import { MAX_TILT_DEG, compassPose } from "./compass";

const rad = (deg) => (deg * Math.PI) / 180;

describe("compassPose", () => {
  it("turns the dial back by the heading, so N points at true north", () => {
    expect(compassPose(rad(330), rad(-35)).rotateDeg).toBeCloseTo(-330, 6);
    expect(compassPose(rad(0), rad(-35)).rotateDeg).toBeCloseTo(0, 6);
  });

  it("lies flat looking straight down and leans back as the view tilts", () => {
    expect(compassPose(0, rad(-90)).tiltDeg).toBeCloseTo(0, 6);
    expect(compassPose(0, rad(-35)).tiltDeg).toBeCloseTo(55, 6);
  });

  it("stops leaning before it goes edge-on and unreadable", () => {
    expect(compassPose(0, rad(-5)).tiltDeg).toBe(MAX_TILT_DEG);
    expect(compassPose(0, rad(10)).tiltDeg).toBe(MAX_TILT_DEG);
  });

  it("reads the heading as three digits, true", () => {
    expect(compassPose(rad(5), 0).headingText).toBe("005°T");
    expect(compassPose(rad(330.4), 0).headingText).toBe("330°T");
    expect(compassPose(rad(359.6), 0).headingText).toBe("000°T");
    expect(compassPose(rad(-30), 0).headingText).toBe("330°T");
  });
});

describe("Compass3D", () => {
  const fakeCesium = {
    Cartesian2: function Cartesian2(x, y) { this.x = x; this.y = y; },
    BoundingSphere: function BoundingSphere(center, radius) { this.center = center; this.radius = radius; },
    HeadingPitchRange: function HeadingPitchRange(heading, pitch, range) {
      Object.assign(this, { heading, pitch, range });
    },
    Cartesian3: { distance: () => 1200 },
  };

  const fakeViewer = ({ heading = rad(330), pitch = rad(-35), ground = { x: 1 } } = {}) => {
    const listeners = [];
    return {
      listeners,
      isDestroyed: () => false,
      camera: {
        heading,
        pitch,
        positionWC: { x: 0 },
        getPickRay: () => ({}),
        flyTo: jest.fn(),
        flyToBoundingSphere: jest.fn(),
      },
      scene: {
        canvas: { clientWidth: 800, clientHeight: 600 },
        globe: { pick: () => ground },
        postRender: {
          addEventListener: (fn) => {
            listeners.push(fn);
            return () => listeners.splice(listeners.indexOf(fn), 1);
          },
        },
      },
    };
  };

  it("shows where the camera faces, and follows it as it turns", () => {
    const viewer = fakeViewer();
    render(<Compass3D viewer={viewer} Cesium={fakeCesium} />);
    expect(screen.getByText("330°T")).toBeInTheDocument();

    viewer.camera.heading = rad(90);
    viewer.listeners.forEach((fn) => fn());
    expect(screen.getByText("090°T")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Facing 090 degrees true/ })).toBeInTheDocument();
  });

  it("turns the view to face north about the point in the middle of the screen", () => {
    const viewer = fakeViewer();
    render(<Compass3D viewer={viewer} Cesium={fakeCesium} />);
    fireEvent.click(screen.getByRole("button", { name: /Face north/ }));

    const [sphere, { offset }] = viewer.camera.flyToBoundingSphere.mock.calls[0];
    expect(sphere.center).toEqual({ x: 1 });
    expect(offset.heading).toBe(0);
    // Same tilt and distance: only the direction changes.
    expect(offset.pitch).toBeCloseTo(rad(-35), 6);
    expect(offset.range).toBe(1200);
  });

  it("still faces north when looking at the sky", () => {
    const viewer = fakeViewer({ ground: null });
    render(<Compass3D viewer={viewer} Cesium={fakeCesium} />);
    fireEvent.click(screen.getByRole("button", { name: /Face north/ }));
    expect(viewer.camera.flyTo.mock.calls[0][0].orientation.heading).toBe(0);
  });

  it("stops following the camera when it goes away", () => {
    const viewer = fakeViewer();
    const { unmount } = render(<Compass3D viewer={viewer} Cesium={fakeCesium} />);
    unmount();
    expect(viewer.listeners).toHaveLength(0);
  });
});
