import React, { useEffect, useMemo, useState } from "react";
import { CircleMarker, ImageOverlay, MapContainer, Polygon, Polyline, TileLayer, Tooltip, useMap, useMapEvents } from "react-leaflet";
import "leaflet/dist/leaflet.css";
import api from "../auth/api";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { MAP_STYLES, getMapStyle } from "../mapStyles/mapStyles";
import SlopeLayer from "../terrain/SlopeLayer";
import SlopeOpacityControl from "../terrain/SlopeOpacityControl";
import useSlopeOpacity from "../terrain/slopeOpacity";
import { mgrsToLatLon } from "../../utils/mgrs";
import { parseCoordinate } from "../../utils/coordParse";
import { approachArrows, boundaryOf } from "./surveyDiagram";
import { spotWords } from "./Obstacles";

/*
 * The survey's map (docs/LZ_SURVEYS.md, the editor's survey mode): the LZ/PZ diagram's target and boundary, the best approach and
 * exit as arrows, and the slope map with its contour lines from the survey's own measurement, drawn by the planning map's layer
 * (SlopeLayer) at the person's own opacity. A map of its own, not the planning map: a survey's LZ is never one of the session's
 * LZ/PZs, so it can never be saved to the Library or a Mission Pack by the planning tools' Save.
 *
 * Tools: set the target (a click, or a grid or coordinate typed), find the boundary with the model at the target (/analyze-field,
 * as the LZ/PZ panel's Analyze does), or draw it a corner at a click.
 *
 * What the LiDAR found (step L4) is a layer of its own: the tallest thing standing in each 2 m cell round the LZ, in four bands, and
 * the tallest in the LZ and within 100 m of it, marked. For the admin to read; the page does not print it.
 */

/** The tree-height picture's bands, as the server colours them (survey/obstacles.py's HEIGHT_BANDS). */
const HEIGHT_BANDS = [
  ["1–5 m", "rgba(255, 236, 160, 0.9)"],
  ["5–15 m", "rgba(255, 170, 60, 0.9)"],
  ["15–30 m", "rgba(232, 74, 40, 0.9)"],
  ["30 m and more", "rgba(150, 20, 92, 0.9)"],
];

const DEFAULT_CENTER = [34.6, -84.1];

const Recentre = ({ target }) => {
  const map = useMap();
  const key = target ? `${target.lat.toFixed(6)},${target.lon.toFixed(6)}` : null;
  useEffect(() => {
    if (target) map.setView([target.lat, target.lon], Math.max(map.getZoom(), 16));
    // Only when the target moves.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return null;
};

const Clicks = ({ onClick }) => {
  useMapEvents({ click: (event) => onClick?.(event.latlng.lat, event.latlng.lng) });
  return null;
};

/** A grid or coordinate typed as text, as { lat, lon }, or null. */
export const placeOf = (text) => {
  const grid = mgrsToLatLon(String(text ?? "").trim());
  if (grid) return grid;
  const coordinate = parseCoordinate(text);
  return coordinate ? { lat: coordinate.lat, lon: coordinate.lon } : null;
};

const SurveyMap = ({ diagram, field, terrain, obstacles = null, onSetTarget, onSetBoundary, readOnly = false }) => {
  const { show } = useToast();
  const [mode, setMode] = useState(null);
  const [corners, setCorners] = useState([]);
  const [finding, setFinding] = useState(false);
  const [styleId, setStyleId] = useState("satellite");
  const [showSlope, setShowSlope] = useState(true);
  const [showHeights, setShowHeights] = useState(true);
  const [place, setPlace] = useState("");
  const { slopeOpacity, setSlopeOpacity } = useSlopeOpacity();

  const target = diagram?.target ?? null;
  const heights = obstacles?.status === "measured" ? obstacles.heightMap ?? null : null;
  const tallest = obstacles?.status === "measured"
    ? [["Tallest in the LZ", obstacles.inside], ["Tallest within 100 m", obstacles.around]].filter(([, spot]) => spot)
    : [];
  const boundary = boundaryOf(diagram);
  const arrows = useMemo(() => approachArrows(target, field), [target, field]);
  const style = getMapStyle(styleId);

  const click = (lat, lon) => {
    if (mode === "target") {
      onSetTarget(lat, lon);
      setMode(null);
    } else if (mode === "boundary") {
      setCorners((list) => [...list, [lat, lon]]);
    }
  };

  const goTo = (event) => {
    event.preventDefault();
    const found = placeOf(place);
    if (!found) {
      show({ message: "That is not a grid or a coordinate.", tone: "warn" });
      return;
    }
    onSetTarget(found.lat, found.lon);
    setPlace("");
  };

  const finishBoundary = () => {
    if (corners.length < 3) {
      show({ message: "A boundary needs at least three corners.", tone: "warn" });
      return;
    }
    onSetBoundary(corners);
    setCorners([]);
    setMode(null);
  };

  const findBoundary = async () => {
    if (!target) return;
    setFinding(true);
    try {
      const answer = (await api.post("/analyze-field", { lat: target.lat, lon: target.lon })).data;
      if (Array.isArray(answer?.suggested_lz) && answer.suggested_lz.length >= 3) {
        onSetBoundary(answer.suggested_lz);
        show({ message: "Boundary found. Check it against the imagery, then measure." });
      } else {
        show({ message: "No boundary was found at the target. Draw it instead.", tone: "warn" });
      }
    } catch (err) {
      show({
        message: err?.response?.status === 400 ? "No distinct area at the target. Draw the boundary instead." : "The boundary could not be found. Try again, or draw it.",
        tone: "warn",
      });
    } finally {
      setFinding(false);
    }
  };

  return (
    <div className="survey-map">
      {!readOnly && (
        <div className="survey-map__tools" role="toolbar" aria-label="Map tools">
          <form className="survey-map__place" onSubmit={goTo}>
            <input
              className="ui-input ui-input--36"
              aria-label="Target grid or coordinate"
              placeholder="Target: grid or lat/long"
              value={place}
              onChange={(event) => setPlace(event.target.value)}
            />
            <button type="submit" className="ui-btn ui-btn--34">Set target</button>
          </form>
          {field?.grid && placeOf(field.grid) && (
            <button type="button" className="ui-btn ui-btn--34" onClick={() => { const p = placeOf(field.grid); onSetTarget(p.lat, p.lon); }}>
              Target at the survey's grid
            </button>
          )}
          <button
            type="button"
            className={`ui-btn ui-btn--34${mode === "target" ? " ui-btn--primary" : ""}`}
            aria-pressed={mode === "target"}
            onClick={() => setMode(mode === "target" ? null : "target")}
          >
            <Icon name="crosshair" size={14} />
            Click to place target
          </button>
          <button type="button" className="ui-btn ui-btn--34" disabled={!target || finding} onClick={findBoundary}>
            <Icon name="search" size={14} />
            {finding ? "Finding…" : "Find boundary"}
          </button>
          {mode === "boundary" ? (
            <>
              <button type="button" className="ui-btn ui-btn--34 ui-btn--primary" onClick={finishBoundary}>
                Finish ({corners.length})
              </button>
              <button type="button" className="ui-btn ui-btn--34" onClick={() => { setCorners([]); setMode(null); }}>Cancel</button>
            </>
          ) : (
            <button type="button" className="ui-btn ui-btn--34" disabled={!target} onClick={() => { setCorners([]); setMode("boundary"); }}>
              <Icon name="hexagon" size={14} />
              Draw boundary
            </button>
          )}
          {boundary && mode !== "boundary" && (
            <button type="button" className="ui-btn ui-btn--34 ui-btn--ghost" onClick={() => onSetBoundary(null)}>Clear boundary</button>
          )}
        </div>
      )}
      <div className="survey-map__frame">
        {/* Over the map, so the map does not move under the pointer as a tool starts. */}
        <div className="survey-map__hint" role="status">
          {mode === "target" ? "Click the touchdown point." : mode === "boundary" ? "Click each corner of the LZ, then Finish." : ""}
        </div>
        <MapContainer center={target ? [target.lat, target.lon] : DEFAULT_CENTER} zoom={target ? 16 : 9} className="survey-map__leaflet" maxZoom={20}>
          <TileLayer key={style.id} url={style.url} {...style.options} />
          <Recentre target={target} />
          <Clicks onClick={click} />
          {terrain && <SlopeLayer terrainData={terrain} shown={showSlope} opacity={slopeOpacity / 100} />}
          {heights && showHeights && <ImageOverlay url={heights.overlay} bounds={heights.bounds} opacity={1} />}
          {showHeights && tallest.map(([label, spot]) => (
            <CircleMarker key={label} center={[spot.lat, spot.lon]} radius={7} pathOptions={{ color: "#a21caf", weight: 3, fillOpacity: 0 }}>
              <Tooltip>{label}: {spotWords(spot)}</Tooltip>
            </CircleMarker>
          ))}
          {boundary && (
            <Polygon positions={boundary} pathOptions={{ color: "#fbbf24", weight: 2, dashArray: "6 4", fillOpacity: 0.05 }} />
          )}
          {corners.length > 0 && <Polyline positions={corners} pathOptions={{ color: "#38bdf8", weight: 2, dashArray: "4 4" }} />}
          {corners.map((corner, i) => (
            // Corners are drawn in order and never move, so a corner's place in the list is what it is.
            <CircleMarker key={i} center={corner} radius={4} pathOptions={{ color: "#38bdf8", fillOpacity: 1 }} />
          ))}
          {arrows.approach && (
            <>
              <Polyline positions={arrows.approach.line} pathOptions={{ color: "#22c55e", weight: 3 }}>
                <Tooltip sticky>Best approach {field.approachDeg}°</Tooltip>
              </Polyline>
              <Polyline positions={arrows.approach.head} pathOptions={{ color: "#22c55e", weight: 3 }} />
            </>
          )}
          {arrows.exit && (
            <>
              <Polyline positions={arrows.exit.line} pathOptions={{ color: "#f97316", weight: 3, dashArray: "8 5" }}>
                <Tooltip sticky>Best exit {field.exitDeg}°</Tooltip>
              </Polyline>
              <Polyline positions={arrows.exit.head} pathOptions={{ color: "#f97316", weight: 3 }} />
            </>
          )}
          {target && (
            <CircleMarker center={[target.lat, target.lon]} radius={6} pathOptions={{ color: "#fff", weight: 2, fillColor: "#ef4444", fillOpacity: 1 }}>
              <Tooltip>Touchdown point{target.mgrs ? `: ${target.mgrs}` : ""}</Tooltip>
            </CircleMarker>
          )}
        </MapContainer>
      </div>
      <div className="survey-map__layers">
        <div role="radiogroup" aria-label="Base map" className="survey-seg">
          {MAP_STYLES.filter((s) => s.id !== "vfr-sectional").map((s) => (
            <button key={s.id} type="button" role="radio" aria-checked={styleId === s.id} className="survey-seg__opt" onClick={() => setStyleId(s.id)}>
              {s.label}
            </button>
          ))}
        </div>
        <label className="survey-check">
          <input type="checkbox" checked={showSlope} disabled={!terrain} onChange={(event) => setShowSlope(event.target.checked)} />
          Slope map and contours
        </label>
        {terrain && showSlope && <SlopeOpacityControl value={slopeOpacity} onChange={setSlopeOpacity} />}
        {!terrain && <span className="survey-muted">Measure to see the slope map.</span>}
        {heights && (
          <>
            <label className="survey-check">
              <input type="checkbox" checked={showHeights} onChange={(event) => setShowHeights(event.target.checked)} />
              What stands (LiDAR)
            </label>
            {showHeights && (
              <ul className="survey-legend" aria-label="Height above the ground">
                {HEIGHT_BANDS.map(([words, colour]) => (
                  <li key={words}><span className="survey-legend__swatch" style={{ background: colour }} aria-hidden="true" />{words}</li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default SurveyMap;
