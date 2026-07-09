/**
 * UH-60L planning profile, extracted from the UH60L.vidx vehicle installation
 * bundled in the mission template (Vehicle Installations/UH60L.vidx →
 * Configurations/Default/*.xml). The vidx's true performance model is a
 * native VPM DLL that only AMPS can run, so exact fuel/power numbers get
 * recalculated there on import — these defaults make in-app planning line up
 * with what AMPS starts from.
 */
export const UH60L_PROFILE = {
  name: "UH-60L",
  // Standard Aircraft Preferences.xml: AirspeedValue "raw 100 Indicated Knot",
  // max "193 I".
  defaultAirspeedKts: 100,
  defaultAirspeedType: "indicated",
  maxIndicatedKts: 193,
  // Standard Aircraft Preferences.xml: PlanAltitudeValue "50 A" (feet AGL),
  // plan altitude limits -2000..20000 ft MSL.
  defaultAltitudeFt: 50,
  defaultAltitudeRef: "agl",
  minAltitudeFtMsl: -2000,
  maxAltitudeFtMsl: 20000,
  // FPM Preferences.xml Standard cruise mode ships with Fuel Flow 960 lb/hr
  // (16,000 lb gross weight baseline).
  defaultFuelFlowLbHr: 960,
  defaultGrossWeightLb: 16000,
};

/** Airspeed reference types the planner (and AMPS AirspeedValue) supports. */
export const AIRSPEED_TYPES = [
  { value: "ground", label: "GS (Ground)", ampsName: "Ground" },
  { value: "indicated", label: "KIAS (Indicated)", ampsName: "Indicated" },
  { value: "true", label: "KTAS (True)", ampsName: "True" },
];
