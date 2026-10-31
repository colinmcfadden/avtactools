/**
 * The brief's formats, as bundled with the web. The files in ./templates are copies of contracts/briefs (Create React
 * App cannot import from outside src/); src/contracts/briefTemplates.test.js fails if a copy differs from its
 * original by a byte, so change contracts/briefs and copy it here.
 */
import amb from "./templates/amb.json";
import hasty from "./templates/hasty.json";
import bindingsFile from "./templates/bindings.json";
import constantsFile from "./templates/constants.json";
import standards from "./templates/standards.json";

export const BRIEF_TEMPLATES = Object.freeze({ amb, hasty });

/** The formats a person may choose when making a brief, in the order offered. */
export const BRIEF_FORMATS = Object.freeze([
  { id: "amb", title: amb.shortTitle },
  { id: "hasty", title: hasty.shortTitle },
]);

export const ROLES = Object.freeze(["PZ", "LZ", "FARP", "HA", "LAAGER", "FALSE_LZ"]);

/** What a role is called in a sentence. */
export const ROLE_NAMES = Object.freeze({
  PZ: "PZ", LZ: "LZ", FARP: "FARP", HA: "holding area", LAAGER: "laager", FALSE_LZ: "false insertion LZ",
});

/**
 * Everything compileBrief needs to know about a format: the template, the bindings it may use, the constants and
 * the standards. Null for a format this version does not have.
 */
export const briefFormat = (id) => {
  const template = BRIEF_TEMPLATES[id];
  if (!template) return null;
  return { template, bindings: bindingsFile.bindings, constants: constantsFile.constants, standards };
};
