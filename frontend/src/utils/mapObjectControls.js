import L from "leaflet";

const iconAttrs =
  'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"';

// Move and rotate are the redesign's icons (feature/ui/Icon.jsx: Lucide's move and rotate-cw), written out as
// markup because Leaflet's divIcons take HTML; mapObjectControls.test.js holds them to Icon's.
export const MOVE_ICON_PATHS =
  '<path d="M12 2v20"></path><path d="m15 19-3 3-3-3"></path><path d="m19 9 3 3-3 3"></path><path d="M2 12h20"></path><path d="m5 9-3 3 3 3"></path><path d="m9 5 3-3 3 3"></path>';
export const ROTATE_ICON_PATHS =
  '<path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"></path><path d="M21 3v5h-5"></path>';

const icons = {
  move: `<svg ${iconAttrs}>${MOVE_ICON_PATHS}</svg>`,
  drag: `<svg ${iconAttrs}>${MOVE_ICON_PATHS}</svg>`,
  rotate: `<svg ${iconAttrs}>${ROTATE_ICON_PATHS}</svg>`,
  resize: `<svg ${iconAttrs}><path d="M8 3H3v5M16 3h5v5M8 21H3v-5M21 16v5h-5"/><path d="m3 8 6-6m6 0 6 6M3 16l6 6m6 0 6-6"/></svg>`,
};

export const mapObjectControlMarkup = ({
  type,
  title,
  tone = "blue",
  size = 34,
  className = "",
}) => `
  <div
    class="map-object-control map-object-control--${type} map-object-control--${tone} ${className}"
    data-map-control="${type}"
    title="${title}"
    role="button"
    aria-label="${title}"
    style="width:${size}px;height:${size}px"
  >${icons[type]}</div>`;

export const createMapObjectHandleIcon = ({
  type,
  title,
  tone = "blue",
  size = 32,
  className = "",
}) =>
  L.divIcon({
    className: `map-object-handle-icon ${className}`,
    html: mapObjectControlMarkup({ type, title, tone, size }),
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
