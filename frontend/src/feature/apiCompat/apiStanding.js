/*
 * How this build of the web app stands with the API the server serves (contracts/scripts/api_version.py).
 * The majors it talks are in src/apiSupport.json, held to what the server serves by that script's check;
 * the server says what it serves in GET /api/config (apiVersion, apiMajors). Android's ApiSupport is the
 * same rule.
 */

import support from "../../apiSupport.json";

export const SUPPORTED = "supported";
export const APP_TOO_OLD = "appTooOld";
export const SERVER_TOO_OLD = "serverTooOld";

/** The majors this build talks. */
export const BUILT_MAJORS = support.apiMajors;

const VERSION = /^(\d{1,4})\.\d{1,4}\.\d{1,4}$/;

/**
 * The API majors a config says the server serves: its list, else its version's major, else [1] for a
 * server from before either field (API 1 is what those served). A version that cannot be read is null:
 * nothing known.
 */
export const servedMajors = (config) => {
  if (!config || typeof config !== "object") return null;
  if (Array.isArray(config.apiMajors) && config.apiMajors.length > 0) return config.apiMajors;
  if (config.apiVersion == null) return [1];
  const match = VERSION.exec(String(config.apiVersion).trim());
  return match ? [Number(match[1])] : null;
};

/** Where a build talking `majors` stands with a server serving `served`. Nothing known is supported. */
export const standingWith = (served, majors = BUILT_MAJORS) => {
  if (!Array.isArray(served) || served.length === 0) return SUPPORTED;
  if (served.some((major) => majors.includes(major))) return SUPPORTED;
  return Math.max(...served) > Math.max(...majors) ? APP_TOO_OLD : SERVER_TOO_OLD;
};
