/**
 * Draws placed routes (see routeScene.placeRoutes) into a Cesium entity
 * collection. Drawing only — every height was decided before it got here.
 */

const LABEL_BACKGROUND = [0.03, 0.05, 0.08, 0.78];
const MISMATCH_COLOR = "#fbbf24";

export const drawRoutes = (Cesium, entities, scene) => {
  const {
    ArcType, Cartesian2, Cartesian3, Color, HorizontalOrigin, LabelStyle,
    NearFarScalar, PolylineDashMaterialProperty, VerticalOrigin,
  } = Cesium;

  const toPositions = (points) => Cartesian3.fromDegreesArrayHeights(points.flat());

  // One change notification for the whole redraw, not one per entity.
  entities.suspendEvents();
  try {
    entities.removeAll();

    for (const route of scene) {
      const color = Color.fromCssColorString(route.color || "#38bdf8");

      for (const line of route.lines) {
        entities.add({
          polyline: {
            positions: toPositions(line),
            width: 3,
            material: color,
            // Where something hides the route it still shows, dashed and
            // dimmed. With terrain depth testing off (see Viewer3D) that
            // something is the point cloud: a leg flown through the canopy
            // draws dashed across it, which is exactly what a crew needs to see.
            depthFailMaterial: new PolylineDashMaterialProperty({
              color: color.withAlpha(0.6),
              dashLength: 14,
            }),
            // Straight between samples: they are already 50 m apart and carry
            // their own heights, which a geodesic resample would re-interpolate.
            arcType: ArcType.NONE,
          },
        });
      }

      // The curtain from the route down to the ground is what makes height
      // readable in 3D; a line alone floats with no sense of how high it is.
      for (const wall of route.walls) {
        entities.add({
          wall: {
            positions: toPositions(wall.positions),
            minimumHeights: wall.groundM,
            material: color.withAlpha(0.2),
          },
        });
      }

      for (const marker of route.markers) {
        // Nothing to drop to from a point already on the ground.
        if (marker.groundM != null && marker.heightM - marker.groundM > 1) {
          entities.add({
            polyline: {
              positions: toPositions([[marker.lon, marker.lat, marker.groundM],
                                      [marker.lon, marker.lat, marker.heightM]]),
              width: 1.5,
              material: Color.WHITE.withAlpha(0.75),
              arcType: ArcType.NONE,
            },
          });
        }

        entities.add({
          position: Cartesian3.fromDegrees(marker.lon, marker.lat, marker.heightM),
          point: {
            pixelSize: marker.ptType === "target" ? 11 : 8,
            color: Color.WHITE,
            outlineColor: color,
            outlineWidth: 3,
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
          },
          label: {
            text: marker.label,
            font: "600 12px sans-serif",
            fillColor: marker.mismatch ? Color.fromCssColorString(MISMATCH_COLOR) : Color.WHITE,
            outlineColor: Color.BLACK,
            outlineWidth: 2,
            style: LabelStyle.FILL_AND_OUTLINE,
            showBackground: true,
            backgroundColor: new Color(...LABEL_BACKGROUND),
            backgroundPadding: new Cartesian2(6, 4),
            horizontalOrigin: HorizontalOrigin.CENTER,
            verticalOrigin: VerticalOrigin.BOTTOM,
            pixelOffset: new Cartesian2(0, -10),
            // Labels stay readable behind ridges; they are what the route
            // points mean, and a terrain-hidden label is no use to anyone.
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
            // Smaller with distance so a long route does not become a wall of text.
            scaleByDistance: new NearFarScalar(1500, 1.0, 60000, 0.55),
          },
        });
      }
    }
  } finally {
    entities.resumeEvents();
  }
};

/** Every drawn route position, for framing the camera on the routes. */
export const routePositions = (Cesium, scene) =>
  scene.flatMap((route) => route.lines.flat())
    .map(([lon, lat, height]) => Cesium.Cartesian3.fromDegrees(lon, lat, height));
