/*
 * What the field survey records for one LZ, in the sections of the unit's HLZ survey book (docs/LZ_SURVEYS.md §1). Every value is
 * text as the survey wrote it. The keys are the server's (backend/survey/fields.py), which refuses any other; both are held to
 * contracts/fixtures/surveys/donovan.json.
 */

export const FIELD_SECTIONS = [
  {
    id: "location",
    title: "Location",
    fields: [
      { key: "grid", label: "Grid", placeholder: "e.g. 16S GD 6341 3199" },
      { key: "latLong", label: "Lat/long", placeholder: "e.g. N 34 35.579 W084 07.402" },
      { key: "ppr", label: "PPR", placeholder: "Who to call, and the number" },
    ],
  },
  {
    id: "approach",
    title: "Approach and capacity",
    fields: [
      { key: "approachDeg", label: "Best approach (° true)", placeholder: "e.g. 360", short: true },
      { key: "exitDeg", label: "Best exit (° true)", placeholder: "e.g. 360", short: true },
      { key: "maxAcDay", label: "Max A/C, day", placeholder: "e.g. 2", short: true },
      { key: "maxAcNight", label: "Max A/C, night", placeholder: "e.g. 2", short: true },
      { key: "maxWeight", label: "Max weight", placeholder: "Only if the LZ limits it", short: true },
    ],
  },
  {
    id: "ground",
    title: "Ground",
    fields: [
      { key: "elevationFt", label: "Elevation (ft MSL)", placeholder: "e.g. 1850", short: true },
      { key: "size", label: "Size", placeholder: "e.g. 500 x 300 ft", short: true },
      { key: "surface", label: "Surface", placeholder: "e.g. Grass" },
      { key: "slope", label: "Slope felt on landing", placeholder: "e.g. -10° roll left side low" },
    ],
  },
  {
    id: "hazards",
    title: "Hazards and notes",
    fields: [
      { key: "hazards", label: "Hazards", multiline: true },
      { key: "warning", label: "Warning", multiline: true },
      { key: "remarks", label: "Notes", multiline: true, hint: "One per line: they print numbered." },
    ],
  },
  {
    id: "profile",
    title: "Survey profile",
    fields: [
      { key: "profileObstacleFt", label: "Obstacle height (ft)", placeholder: "e.g. 100", short: true },
      { key: "profileDistanceM", label: "At a distance of (m)", placeholder: "e.g. 200", short: true },
    ],
  },
  {
    id: "surveyed",
    title: "Surveyed",
    fields: [
      { key: "surveyedOn", label: "When", placeholder: "e.g. OCT 2023", short: true },
      { key: "surveyUnit", label: "Unit", placeholder: "e.g. AASF 1", short: true },
      { key: "surveyedBy", label: "By", placeholder: "e.g. CW3 J. Simpson, ASO" },
    ],
  },
];

export const FIELD_KEYS = FIELD_SECTIONS.flatMap((section) => section.fields.map((f) => f.key));

export const FIELD_LABELS = Object.fromEntries(FIELD_SECTIONS.flatMap((section) => section.fields.map((f) => [f.key, f.label])));

/** Every field, blank where the survey has nothing. */
export const fullField = (field) => Object.fromEntries(FIELD_KEYS.map((key) => [key, typeof field?.[key] === "string" ? field[key] : ""]));

export const STATUS_WORDS = { draft: "Draft", in_review: "In review", published: "Published" };
