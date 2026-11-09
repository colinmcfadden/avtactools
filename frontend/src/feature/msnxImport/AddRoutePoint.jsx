import React, { useState } from "react";
import TargetField from "../../components/TargetField";
import Icon from "../ui/Icon";

/** What a choice in the field makes of a new route point: a place or a local point gives it its name, a local point its charted elevation. */
export const pointFromChoice = (choice) => {
  const { place } = choice;
  return {
    lat: choice.lat,
    lon: choice.lon,
    name: place ? (place.ident || place.name || "").toUpperCase() : "",
    chartElevationFt: place?.localPoint && Number.isFinite(place.elevationFt) ? place.elevationFt : undefined,
  };
};

/**
 * The route plan's "add a point": the search field (TargetField) reading a grid or a lat/long as it is typed and finding
 * places and local points by name. It stays open, ready for the next point, until it is closed.
 */
const AddRoutePoint = ({ where, near, searchPlaces, localPoints, onSearchStart, onAdd, onClose, failed }) => {
  const [text, setText] = useState("");
  // A new field for each point: empty, with the focus in it again.
  const [added, setAdded] = useState(0);
  return (
    <div
      className="plan__add"
      onKeyDown={(event) => {
        // The field takes the first Escape to close its list; the next closes this.
        if (event.key === "Escape") {
          event.stopPropagation();
          onClose();
        }
      }}
    >
      <div className="plan__add-head">
        <span>{where}</span>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--26 ui-btn--square" aria-label="Stop adding points" onClick={onClose}>
          <Icon name="x" size={14} />
        </button>
      </div>
      <TargetField
        key={added}
        value={text}
        onChange={setText}
        onChoose={(choice) => {
          onAdd(pointFromChoice(choice));
          setText("");
          setAdded((n) => n + 1);
        }}
        near={near}
        searchPlaces={searchPlaces}
        localPoints={localPoints}
        autoFocus
        goLabel="Add"
        placeholder="Grid, lat/long, place or local point"
        widePlaceholder=""
        rowClassName="plan__add-row"
        inputClassName="plan__input plan__input--text"
        buttonClassName="ui-btn ui-btn--primary ui-btn--28"
        inputProps={{ "aria-label": `Add a point: grid, lat/long, place or local point. ${where}`, onFocus: onSearchStart }}
      />
      {failed && (
        <div className="plan__warning" role="status">
          <Icon name="alertTriangle" size={13} />
          <span>{failed}</span>
        </div>
      )}
    </div>
  );
};

export default AddRoutePoint;
