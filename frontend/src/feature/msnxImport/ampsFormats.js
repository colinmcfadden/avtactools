/**
 * Serializers for the AMPS/Mission X attribute value formats found in
 * mission/*.xml (see the mission template for reference values):
 *   AirspeedValue      "raw 40 Ground Knot"
 *   CruiseWind         "0 T/0 m/s"
 *   PlanAltitudeValue  "raw15.24 m Foot AGL"
 *   CmdAlt             "940.00320000000011 MM"       (meters MSL)
 *   Elevation          "924.7632000000001 m User"
 *   CmdClockTime       "1/7/2026 6:24:09.7698 PM"
 */

export const FT_TO_M = 0.3048;
export const KTS_TO_MPS = 0.514444;

const AMPS_AIRSPEED_NAMES = {
  ground: "Ground",
  indicated: "Indicated",
  true: "True",
};

export const formatAmpsAirspeed = (airspeed) =>
  `raw ${airspeed.value} ${AMPS_AIRSPEED_NAMES[airspeed.type] || "Ground"} Knot`;

export const formatAmpsWind = (dirTrue, speedKts) =>
  `${dirTrue} T/${speedKts * KTS_TO_MPS} m/s`;

export const formatAmpsPlanAltitude = (valueFt, ref) =>
  `raw${valueFt * FT_TO_M} m Foot ${ref === "msl" ? "MSL" : "AGL"}`;

export const formatAmpsCmdAlt = (mslFt) => `${mslFt * FT_TO_M} MM`;

export const formatAmpsElevation = (groundFt) => `${groundFt * FT_TO_M} m User`;

export const formatAmpsClockTime = (date) => {
  let hours12 = date.getHours() % 12;
  if (hours12 === 0) hours12 = 12;
  const meridiem = date.getHours() < 12 ? "AM" : "PM";
  const mm = String(date.getMinutes()).padStart(2, "0");
  const ss = String(date.getSeconds()).padStart(2, "0");
  return (
    `${date.getMonth() + 1}/${date.getDate()}/${date.getFullYear()} ` +
    `${hours12}:${mm}:${ss}.0000 ${meridiem}`
  );
};
