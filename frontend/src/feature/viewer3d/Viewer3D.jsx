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
        shading.eyeDomeLightingStrength = 1.0;
        shading.eyeDomeLightingRadius = 1.0;
        // Points are drawn large enough to meet their neighbours. Below this
        // the survey's own spacing shows as black gaps between dots — the
        // single thing that most stops a cloud reading as ground rather than
        // as a scatter plot.
        shading.maximumAttenuation = 10;
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

        viewer.zoomTo(tileset).catch(() => {
          /* the camera can fail to settle without a render loop; the tileset
             is already loaded and visible once frames resume */
        });
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
