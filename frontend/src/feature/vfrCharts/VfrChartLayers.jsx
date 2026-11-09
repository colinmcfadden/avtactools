import React, { useMemo } from "react";
import { TileLayer } from "react-leaflet";
import { getMapStyle } from "../mapStyles/mapStyles";
import { VFR_STYLE_ID, chartLayerOptions, leafletBounds } from "./vfrCharts";

/**
 * The VFR map's layers (docs/VFR_CHARTS.md §4.6): the live build within its bounds, and under it one ArcGIS layer for each place
 * the build has no charts; with no live build, ArcGIS everywhere. Only the chart layer's failures ask for the config again.
 */
export default function VfrChartLayers({ charts }) {
  const { known, live, tileFailed } = charts;
  const handlers = useMemo(() => ({ tileerror: tileFailed }), [tileFailed]);
  if (!known) return null;

  const arcgis = getMapStyle(VFR_STYLE_ID);
  if (!live) return <TileLayer key="arcgis" url={arcgis.url} crossOrigin="anonymous" {...arcgis.options} />;
  return (
    <>
      {live.fallback.map((box) => (
        <TileLayer
          key={box.join()}
          url={arcgis.url}
          crossOrigin="anonymous"
          {...arcgis.options}
          bounds={leafletBounds(box)}
          zIndex={1}
        />
      ))}
      {/* Keyed by the build: a new one has its own URL, bounds and levels, and a layer's options are fixed once made. */}
      <TileLayer
        key={live.build}
        url={live.tiles}
        crossOrigin="anonymous"
        {...chartLayerOptions(live)}
        zIndex={2}
        eventHandlers={handlers}
      />
    </>
  );
}
