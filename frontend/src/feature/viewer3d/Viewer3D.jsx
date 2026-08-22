import React, { useEffect, useRef, useState } from "react";
import { loadCesium } from "./cesiumSetup";
import { tilesetResource } from "./tilesetResource";
import { MAPBOX_IMAGERY } from "../mapStyles/mapStyles";
import { createTerrainProvider } from "./terrainProvider";
// Cesium's own stylesheet sizes .cesium-widget to fill its container. Without
// it the widget has no dimensions and the canvas falls back to its 300x150
// default, so the scene renders into a postage stamp regardless of layout.
import "cesium/Build/Cesium/Widgets/widgets.css";
import "./viewer3d.css";

// Colour comes baked into each point's RGB by the tile build (see
// backend/lidar/pipeline.py). No style is applied here at all.
//
// The earlier version styled on ${Classification}, which silently painted
// every cloud one flat grey: .pnts files carry POSITION and RGB and no
// classification, so the condition matched nothing and every point fell
// through to the fallback colour. The only other thing a style set was
// pointSize, which attenuation below overrides anyway.

// Opening view: looking down at 35 degrees from the north-west, far enough
// back to hold the whole area of interest. Shallow enough that treelines and
// obstruction heights read against the horizon, steep enough to still see the
// landing surface itself.
const CAMERA_HEADING_DEG = -30;
const CAMERA_PITCH_DEG = -35;
const CAMERA_RANGE_FACTOR = 2.2;

// Point size and shading are a matter of taste and of display density, and
// getting them right by exchanging screenshots is slow. These are the
// defaults; window.__viewer3d.tune() changes them live in development.
const DEFAULT_POINT_SIZE_PX = 5;

/** What the scene actually contains, for diagnosing a render from outside it. */
const report = (viewer, tileset) => {
  const scene = viewer.scene;
  const layers = scene.imageryLayers;
  const camera = scene.camera;
  const carto = camera.positionCartographic;
  const globe = scene.globe;
  return {
    imageryLayers: layers.length,
    imageryReady: Array.from({ length: layers.length }, (_unused, index) => {
      const provider = layers.get(index).imageryProvider;
      return {
        url: String(provider?.url || "").slice(0, 60),
        ready: provider?.ready !== false,
        errors: provider?.errorEvent?.numberOfListeners ?? null,
      };
    }),
    globeShown: globe?.show,
    globeBaseColor: globe?.baseColor?.toCssColorString?.(),
    terrainProvider: scene.terrainProvider?.constructor?.name,
    terrainTilesLoaded: globe?.tilesLoaded,
    cameraHeightM: carto ? Math.round(carto.height) : null,
    cameraPitchDeg: Math.round((camera.pitch * 180) / Math.PI),
    tilesetPointsLoaded: tileset?.pointCloudShading ? tileset.totalMemoryUsageInBytes : null,
    tilesLoaded: tileset?.tilesLoaded,
    statistics: tileset ? {
      visited: tileset.statistics?.visited,
      selected: tileset.statistics?.selected,
      numberOfPointsSelected: tileset.statistics?.numberOfPointsSelected,
    } : null,
  };
};

/**
 * A 3D view of one LiDAR point cloud tileset.
 *
 * The tileset is generated per area of interest by backend/lidar and holds
 * classified 3DEP returns already reprojected into WGS84 geocentric metres,
 * which is the frame Cesium renders in. Nothing is transformed here — if the
 * heights are wrong they were wrong when the tiles were built.
 */
const Viewer3D = ({ tilesetUrl, requiresAuth = true, showTerrain = true,
                   onReady }) => {
  const containerRef = useRef(null);
  const [status, setStatus] = useState({ state: "loading", detail: "" });

  useEffect(() => {
    let cancelled = false;
    let viewer = null;
    let resizeObserver = null;

    loadCesium()
      .then(async (Cesium) => {
        if (cancelled || !containerRef.current) return;

        viewer = new Cesium.Viewer(containerRef.current, {
          // The default widgets assume a globe browser. This view is a single
          // site, so anything that only makes sense at planetary scale is off.
          baseLayerPicker: false,
          geocoder: false,
          homeButton: false,
          sceneModePicker: false,
          navigationHelpButton: false,
          animation: false,
          timeline: false,
          fullscreenButton: false,
          infoBox: false,
          selectionIndicator: false,
          // Without this the viewer requests its default Cesium ion base
          // layer during construction, which 401s because no ion token is
          // configured. Harmless — the layer is replaced below either way —
          // but it puts a failed request in the console of every session.
          baseLayer: false,
        });

        viewer.scene.backgroundColor = Cesium.Color.fromCssColorString("#05080d");

        // The ground surface, drawn from the same Mapbox satellite imagery the
        // 2D map uses. Without this the scene is points in a void, which is
        // what made earlier builds unrecognisable as a place regardless of how
        // the points themselves were coloured.
        viewer.imageryLayers.removeAll();
        viewer.imageryLayers.addImageryProvider(
          new Cesium.UrlTemplateImageryProvider({
            url: MAPBOX_IMAGERY.template,
            credit: MAPBOX_IMAGERY.attribution,
            tileWidth: MAPBOX_IMAGERY.tileWidth,
            tileHeight: MAPBOX_IMAGERY.tileHeight,
            maximumLevel: MAPBOX_IMAGERY.maximumLevel,
          }),
        );

        if (showTerrain) {
          const terrain = createTerrainProvider(Cesium);
          if (terrain) viewer.scene.terrainProvider = terrain;
        }
        // Points sit on the surface, so any depth test against it drops the
        // ground returns into the terrain and makes the cloud look eaten.
        viewer.scene.globe.depthTestAgainstTerrain = false;

        // A Resource rather than a bare URL: Cesium fetches this tileset's
        // child tiles itself, and only a Resource carries the bearer token
        // onto those derived requests.
        const source = tilesetResource(Cesium, tilesetUrl, { requiresAuth });
        const tileset = await Cesium.Cesium3DTileset.fromUrl(source, {
          // Points have no surface, so screen-space error is the only lever on
          // how much detail streams in. The default is tuned for buildings; at
          // 8 the viewer was refusing to load the fine tiles at all, which
          // capped how dense the cloud could ever look no matter what was
          // built. An LZ is small enough to afford loading it properly.
          maximumScreenSpaceError: 2,
        });
        if (cancelled) return;

        viewer.scene.primitives.add(tileset);

        // Eye-dome lighting is what stops a point cloud reading as a flat
        // speckled sheet. Points carry no normals, so there is no shading cue
        // at all without it; EDL darkens each point by how much nearer its
        // neighbours are, which outlines canopy and terrain relief. Attenuation
        // sizes points by distance so the near ground reads as a surface rather
        // than separating into dots.
        const shading = tileset.pointCloudShading;
        shading.attenuation = true;
        shading.eyeDomeLighting = true;
        // Full strength at a 1 px radius outlines every individual point,
        // which is what made returns read as separate tiles with borders
        // rather than as a continuous canopy. Enough shading to show relief,
        // not so much that it draws the points.
        shading.eyeDomeLightingStrength = 0.5;
        shading.eyeDomeLightingRadius = 0.5;
        // A cap in CSS pixels, which a retina display then doubles. At 10 that
        // meant 20 physical pixels a point: every return rendered as a fat
        // square with an eye-dome outline round it, and the cloud looked like
        // masonry. Large enough to close the survey's ~0.5 m spacing at
        // working range, and no larger.
        // Attenuation grows point size as the camera approaches and this caps
        // it. Too low and close-up views thin out until vegetation seems to
        // vanish; too high and distant views turn to masonry. 5 holds a
        // surface at working range without either.
        shading.maximumAttenuation = DEFAULT_POINT_SIZE_PX;
        // Sizes points from the tile's own geometric error, so density and
        // point size stay in step as tiles stream in.
        shading.geometricErrorScale = 1.0;

        // Ready means the tileset is in the scene, not that the camera has
        // finished moving. zoomTo resolves only when its flight completes, and
        // a flight needs the render loop — which is paused in a hidden or
        // backgrounded tab, leaving the view stuck on "Loading" forever.
        // Cesium sizes its canvas when the widget is created and then only on
        // window resize. A container that changes size for any other reason —
        // a panel opening, a flex reflow — leaves the canvas stale, so track
        // the element itself.
        resizeObserver = new ResizeObserver(() => {
          if (!viewer.isDestroyed()) viewer.resize();
        });
        resizeObserver.observe(containerRef.current);

        setStatus({ state: "ready", detail: "" });
        onReady?.({ viewer, tileset, Cesium });

        // The browser preview available here cannot composite WebGL, so
        // nothing about how this scene actually renders can be checked from
        // outside it. In development the scene is reachable from the console
        // so its real state can be read rather than inferred:
        //   window.__viewer3d.report()
        if (process.env.NODE_ENV === "development") {
          window.__viewer3d = {
            viewer,
            tileset,
            Cesium,
            report: () => report(viewer, tileset),
            /** Adjust point size and shading live: tune({ size: 8 }) */
            tune: ({ size, edlStrength, edlRadius, screenSpaceError } = {}) => {
              const s = tileset.pointCloudShading;
              if (size !== undefined) s.maximumAttenuation = size;
              if (edlStrength !== undefined) s.eyeDomeLightingStrength = edlStrength;
              if (edlRadius !== undefined) s.eyeDomeLightingRadius = edlRadius;
              if (screenSpaceError !== undefined) {
                tileset.maximumScreenSpaceError = screenSpaceError;
              }
              viewer.scene.requestRender();
              return {
                size: s.maximumAttenuation,
                edlStrength: s.eyeDomeLightingStrength,
                edlRadius: s.eyeDomeLightingRadius,
                screenSpaceError: tileset.maximumScreenSpaceError,
              };
            },
          };
        }

        // Placed explicitly rather than with zoomTo. zoomTo frames the
        // bounding sphere from wherever the camera already is, which for a
        // wide, shallow cloud can leave it nearly level with the ground — or
        // under it, looking at the underside of the terrain, which renders as
        // a flat wash of colour with the points stranded in the middle of it.
        //
        // An LZ is also read obliquely, not from directly overhead: the whole
        // reason for a 3D view is seeing how tall the obstructions are on
        // approach, and that is invisible from straight down.
        const sphere = tileset.boundingSphere;
        viewer.camera.lookAt(
          sphere.center,
          new Cesium.HeadingPitchRange(
            Cesium.Math.toRadians(CAMERA_HEADING_DEG),
            Cesium.Math.toRadians(CAMERA_PITCH_DEG),
            sphere.radius * CAMERA_RANGE_FACTOR,
          ),
        );
        // Releases the camera from the target's reference frame, so the user
        // can orbit and pan freely from here.
        viewer.camera.lookAtTransform(Cesium.Matrix4.IDENTITY);
      })
      .catch((error) => {
        if (!cancelled) {
          setStatus({ state: "error", detail: error?.message || String(error) });
        }
      });

    return () => {
      cancelled = true;
      resizeObserver?.disconnect();
      // Cesium holds a WebGL context and a worker pool. Without an explicit
      // destroy they outlive unmount and the next viewer fails to get a context.
      if (viewer && !viewer.isDestroyed()) viewer.destroy();
    };
  }, [tilesetUrl, requiresAuth, showTerrain, onReady]);

  return (
    <div className="viewer3d">
      <div ref={containerRef} className="viewer3d__canvas" />
      {status.state !== "ready" && (
        <div
          className={`viewer3d__status${
            status.state === "error" ? " viewer3d__error" : ""
          }`}
        >
          <strong>{status.state === "error" ? "3D view failed" : "Loading"}</strong>
          {status.state === "error" ? status.detail : "Preparing point cloud…"}
        </div>
      )}
    </div>
  );
};

export default Viewer3D;
