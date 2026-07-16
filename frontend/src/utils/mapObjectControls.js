import L from "leaflet";

const iconAttrs =
  'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"';

const icons = {
  move: `<svg ${iconAttrs}><path d="M12 3v18M3 12h18"/><path d="m8 7 4-4 4 4M8 17l4 4 4-4M7 8l-4 4 4 4M17 8l4 4-4 4"/></svg>`,
  rotate: `<svg ${iconAttrs}><path d="M20 11a8 8 0 1 1-2.35-5.65L20 8"/><path d="M20 3v5h-5"/></svg>`,
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
