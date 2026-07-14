// Quick-add unit presets, now as MIL-STD-2525C SIDCs (rendered via milsymbol).
// The unit builder covers everything else; these are one-tap common choices.
export const UNIT_TYPES = [
  { id: "infantry", label: "Infantry", sidc: "SFGPUCI--------" },
  { id: "light_infantry", label: "Light Infantry", sidc: "SFGPUCIL-------" },
  { id: "air_assault", label: "Air Assault Infantry", sidc: "SFGPUCIS-------" },
  { id: "airborne", label: "Airborne Infantry", sidc: "SFGPUCIA-------" },
  { id: "mountain", label: "Mountain Infantry", sidc: "SFGPUCIO-------" },
];
