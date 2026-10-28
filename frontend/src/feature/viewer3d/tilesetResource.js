/**
 * Building a Cesium Resource that can reach an authenticated tileset.
 *
 * Cesium fetches a tileset's children itself — a single view pulls dozens of
 * .pnts files — and those requests do not go through the app's axios client.
 * A Resource carries its headers to every derived request, which is what lets
 * the tile endpoints stay behind JWT rather than being opened up to suit the
 * renderer.
 */

import { getAccessToken, refreshAccessToken } from "../auth/session";

const API_BASE_URL = process.env.REACT_APP_API_URL || "";

/**
 * Absolute URL for a path the API returned.
 *
 * API paths are relative to REACT_APP_API_URL, which already ends in `/api`.
 * Static paths (the development demo tileset) are served by the frontend
 * itself and must be left alone — prefixing one with the API base points it at
 * the backend, where nothing is serving it.
 */
export const absoluteTilesetUrl = (url, { fromApi = true } = {}) => {
  if (/^https?:\/\//i.test(url)) return url;
  return fromApi ? `${API_BASE_URL}${url}` : url;
};

/**
 * A refused tile tries once more with the token as it is now. A web session's
 * token lasts 15 minutes and every derived Resource keeps the header it was
 * made with, so a tile asked for later is sent with a lapsed one: it is given
 * the current token, or, when it already had that, a refreshed one.
 */
export const retryWithFreshToken = async (resource, error) => {
  if (error?.statusCode !== 401) return false;
  const sent = resource.headers?.Authorization;
  let token = getAccessToken();
  if (!token || sent === `Bearer ${token}`) token = await refreshAccessToken().catch(() => null);
  if (!token) return false;
  resource.headers = { ...resource.headers, Authorization: `Bearer ${token}` };
  return true;
};

/**
 * A Resource for `url`, carrying the bearer token when one is present.
 *
 * `requiresAuth: false` marks a tileset served as a static file rather than by
 * the API — the development demo — which needs neither the API base nor a
 * token, so it is passed through as a plain string.
 */
export const tilesetResource = (Cesium, url, { requiresAuth = true } = {}) => {
  const absolute = absoluteTilesetUrl(url, { fromApi: requiresAuth });
  const token = requiresAuth ? getAccessToken() : null;
  if (!token) return absolute;
  return new Cesium.Resource({
    url: absolute,
    headers: { Authorization: `Bearer ${token}` },
    retryAttempts: 1,
    retryCallback: retryWithFreshToken,
  });
};
