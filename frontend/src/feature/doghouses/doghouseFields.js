// What a doghouse says and how its fields are written, kept apart from the Leaflet marker so the native apps can be held to it
// (frontend/src/contracts/doghouseFixtures.test.js). Nothing here changes how a doghouse behaves.

/** The way the box points, as the marker turns it: the leading integer of the stored heading ("270°" is 270), or 0. Not wrapped. */
export const doghouseRotation = (dh) => parseInt(dh.heading) || 0;

/** What the four rows and the label show for [dh], the box turned by [rotation] degrees. */
export const doghouseDisplay = (dh, rotation) => {
  const time = (dh.time || "00+00").split("+");
  const airspeed = dh.airspeed ? dh.airspeed.split(" ")[0] : "90";
  return {
    id: dh.id_val,
    heading: Math.round(rotation).toString().padStart(3, "0"),
    minutes: time[0] || "00",
    seconds: time[1] || "00",
    distance: parseFloat(dh.dist) || 0,
    airspeed: parseInt(airspeed) || 90,
  };
};

/** A typed heading as degrees: its leading integer, wrapped once into 0..359 (a very negative one stays negative, as JavaScript's % does). */
export const doghouseHeadingDegrees = (value) => ((parseInt(value) || 0) + 360) % 360;

/** The stored form of a heading in degrees: three digits and a degree sign. */
export const doghouseHeadingText = (degrees) => `${degrees.toString().padStart(3, "0")}°`;

/**
 * What writing [value] into the field [type] ("id", "dist", "airspeed" or one of the two "time" parts) stores on the doghouse. [time] holds
 * what both halves of the time row say at that moment, because either one rewrites the whole.
 */
export const doghouseFieldUpdates = (type, value, time) => {
  const updates = {};
  if (type === "id") updates.id_val = value;
  else if (type === "dist") updates.dist = `${value}km`;
  else if (type === "airspeed") updates.airspeed = `${value} kts`;
  else if (type.startsWith("time")) updates.time = `${time.minutes}+${time.seconds}`;
  return updates;
};

const isLanding = (dh) => dh.role === "landing" || dh.id === "dh2" || dh.id_val === "[RP1]";
const isTakeoff = (dh) => dh.role === "takeoff" || dh.id === "dh1" || dh.id_val === "[SP1]";

/**
 * The doghouses that give the flight data its landing and takeoff headings. `role` is used by new default doghouses; the older id and
 * id_val fallbacks keep existing saved diagrams compatible.
 */
export const flightDoghouses = (doghouses) => {
  const list = Array.isArray(doghouses) ? doghouses : [];
  return { landing: list.find(isLanding), takeoff: list.find(isTakeoff) };
};

/** [flightData] with the landing and takeoff headings the doghouses give; unchanged when there is neither doghouse. */
export const flightDataFromDoghouses = (doghouses, flightData) => {
  const { landing, takeoff } = flightDoghouses(doghouses);
  if (!landing && !takeoff) return flightData;
  return {
    ...flightData,
    landing_hdg: landing ? landing.heading : flightData.landing_hdg,
    takeoff_hdg: takeoff ? takeoff.heading : flightData.takeoff_hdg,
  };
};
