import React, { useEffect, useRef, useState } from "react";
import { loadCesium } from "./cesiumSetup";
import { tilesetResource } from "./tilesetResource";
import { MAPBOX_IMAGERY } from "../mapStyles/mapStyles";
import { createTerrainProvider } from "./terrainProvider";
import { drawRoutes, routePositions } from "./routeEntities";
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

/** Frame a bounding sphere the way the view opens: oblique, from the north-west. */
const frameSphere = (Cesium, camera, sphere, { duration } = {}) => {
  const offset = new Cesium.HeadingPitchRange(
    Cesium.Math.toRadians(CAMERA_HEADING_DEG),
    Cesium.Math.toRadians(CAMERA_PITCH_DEG),
    sphere.radius * CAMERA_RANGE_FACTOR,
  );
  if (duration === undefined) {
    camera.lookAt(sphere.center, offset);
    // Releases the camera from the target's reference frame, so the user
    // can orbit and pan freely from here.
    camera.lookAtTransform(Cesium.Matrix4.IDENTITY);
  } else {
    camera.flyToBoundingSphere(sphere, { offset, duration });
  }
};

// Point size and shading are a matter of taste and of display density, and
// getting them right by exchanging screenshots is slow. These are the
// defaults; window.__viewer3d.tune() changes them live in development.
const DEFAULT_POINT_SIZE_PX = 5;
const POINT_SIZE_RANGE = [2, 16];

// Remembered across sessions. Point size is the one setting that depends on
// the display as much as the data — a retina panel doubles it — so the right
// value is the viewer's to pick, not one this code can know.
const POINT_SIZE_KEY = "avtac.viewer3d.pointSize";

const storedPointSize = () => {
  const stored = Number(localStorage.getItem(POINT_SIZE_KEY));
  return Number.isFinite(stored) && stored >= POINT_SIZE_RANGE[0]
    && stored <= POINT_SIZE_RANGE[1] ? stored : DEFAULT_POINT_SIZE_PX;
};

// The ring is thinned landscape at several metres between points, so pulling
// it in at the core's fidelity would cost bandwidth for detail that is not
// there. Looser error means it streams coarse and stays cheap.
const CONTEXT_SCREEN_SPACE_ERROR = 8;

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
const NO_ROUTES = [];

const Viewer3D = ({ tilesetUrl, contextUrl = null, requiresAuth = true,
                   showTerrain = true, routeScene = NO_ROUTES, onReady }) => {
  const containerRef = useRef(null);
  const [status, setStatus] = useState({ state: "loading", detail: "" });

  // Both tilesets, so the size control reaches the context ring too.
  const sceneRef = useRef({ viewer: null, tilesets: [], Cesium: null, routes: null });
  // The routes as of the latest render, for a viewer that becomes ready after
  // they arrived — the drawing effect below only runs when they change.
  const routeSceneRef = useRef(routeScene);
  routeSceneRef.current = routeScene;
  const [pointSize, setPointSize] = useState(storedPointSize);

  useEffect(() => {
    const { viewer, tilesets } = sceneRef.current;
    tilesets.forEach((t) => {
      if (t && !t.isDestroyed?.()) t.pointCloudShading.maximumAttenuation = pointSize;
    });
    localStorage.setItem(POINT_SIZE_KEY, String(pointSize));
    // The scene renders on demand, so a shading change needs a nudge to show.
    if (viewer && !viewer.isDestroyed()) viewer.scene.requestRender();
  }, [pointSize, status.state]);

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
        //
        // Routes would want it on, so a ridge hides the leg behind it — but
        // tried, it hid far more than that. Distant terrain is drawn coarse,
        // and a route at 50 ft AGL sits below the coarse surface along most of
        // its length, so nearly every leg rendered as hidden. Routes are still
        // hidden by the point cloud, which is the case that matters near the
        // LZ; clearance against terrain belongs in numbers, not occlusion.
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
        // Routes get their own data source, redrawn by the effect below
        // whenever they change. They are deliberately not a dependency of
        // this effect: that would rebuild the whole viewer — WebGL context,
        // point cloud, terrain — every time a point moved in 2D.
        const routes = new Cesium.CustomDataSource("routes");
        viewer.dataSources.add(routes);
        drawRoutes(Cesium, routes.entities, routeSceneRef.current);
        sceneRef.current = { viewer, tilesets: [tileset], Cesium, routes };

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
        // Attenuation grows point size as the camera approaches and this caps
        // it, in CSS pixels, which a retina display then doubles. Too low and
        // close-up views thin out until vegetation seems to vanish; too high
        // and every return draws as a bordered square. The viewer sets it —
        // the right value depends on their display as much as on the data.
        shading.maximumAttenuation = storedPointSize();
        // Sizes points from the tile's own geometric error, so density and
        // point size stay in step as tiles stream in.
        shading.geometricErrorScale = 1.0;

        // The landscape ring, if one was built. Started only once the core is
        // configured and drawing: awaiting it first left the landing point
        // rendering unshaded until the ring arrived, and unshaded for good if
        // it never did.
        if (contextUrl) {
          Cesium.Cesium3DTileset.fromUrl(
            tilesetResource(Cesium, contextUrl, { requiresAuth }),
            { maximumScreenSpaceError: CONTEXT_SCREEN_SPACE_ERROR },
          ).then((ring) => {
            if (cancelled || viewer.isDestroyed()) return;
            viewer.scene.primitives.add(ring);
            const ringShading = ring.pointCloudShading;
            ringShading.attenuation = true;
            ringShading.eyeDomeLighting = true;
            ringShading.eyeDomeLightingStrength = 0.5;
            ringShading.eyeDomeLightingRadius = 0.5;
            ringShading.maximumAttenuation = storedPointSize();
            sceneRef.current.tilesets.push(ring);
          }).catch(() => {
            // Context is optional; losing it must not lose the landing point.
          });
        }

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
        frameSphere(Cesium, viewer.camera, tileset.boundingSphere);
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
  }, [tilesetUrl, contextUrl, requiresAuth, showTerrain, onReady]);

  useEffect(() => {
    const { viewer, Cesium, routes } = sceneRef.current;
    if (status.state !== "ready" || !viewer || viewer.isDestroyed() || !routes) return;
    drawRoutes(Cesium, routes.entities, routeScene);
    viewer.scene.requestRender();
  }, [routeScene, status.state]);

  const hasRoutes = routeScene.some((route) => route.lines.length > 0);

  const frameLz = () => {
    const { viewer, Cesium, tilesets } = sceneRef.current;
    if (!viewer || viewer.isDestroyed() || !tilesets[0]) return;
    frameSphere(Cesium, viewer.camera, tilesets[0].boundingSphere, { duration: 1.2 });
  };

  const frameRoutes = () => {
    const { viewer, Cesium } = sceneRef.current;
    if (!viewer || viewer.isDestroyed()) return;
    const positions = routePositions(Cesium, routeScene);
    if (positions.length === 0) return;
    frameSphere(Cesium, viewer.camera, Cesium.BoundingSphere.fromPoints(positions),
                { duration: 1.2 });
  };

  return (
    <div className="viewer3d">
      <div ref={containerRef} className="viewer3d__canvas" />

      {status.state === "ready" && (
        <div className="viewer3d__camera" role="group" aria-label="Camera">
          <button type="button" onClick={frameLz} title="Frame the landing zone">LZ</button>
          <button
            type="button"
            onClick={frameRoutes}
            disabled={!hasRoutes}
            title={hasRoutes ? "Frame the visible routes" : "No visible routes to show"}
          >
            Routes
          </button>
        </div>
      )}
      {status.state === "ready" && (
        <label className="viewer3d__control">
          <span>Point size</span>
          <input
            type="range"
            min={POINT_SIZE_RANGE[0]}
            max={POINT_SIZE_RANGE[1]}
            step={1}
            value={pointSize}
            onChange={(event) => setPointSize(Number(event.target.value))}
            aria-label="Point size"
          />
          <output>{pointSize}</output>
        </label>
      )}
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
