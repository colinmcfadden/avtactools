/** Saves a Blob the server made as a download named `filename`. */
export const downloadBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Safari starts the download after the click returns: let go of the URL a moment later, not at once.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

/**
 * What the server said went wrong with a request asked for as a Blob (a file), whose error is JSON inside it, or
 * `fallback`; and the app's own words when no answer came at all.
 */
export const blobError = async (err, fallback) => {
  const data = err?.response?.data;
  try {
    if (data && typeof data.text === "function") {
      const said = JSON.parse(await data.text());
      if (typeof said?.error === "string" && said.error) return said.error;
    } else if (typeof data?.error === "string" && data.error) {
      return data.error;
    }
  } catch {
    // Not JSON: the fallback says it.
  }
  return err?.response ? fallback : "The server could not be reached. Check the connection and try again.";
};

/**
 * Downloads what a link names, without the page holding it: the server answers it as an attachment
 * (Content-Disposition), so the browser saves it, of any size, without leaving the page. `filename` names it where the
 * link is the page's own origin; a link to another (the API's) keeps the server's name.
 */
export const downloadUrl = (url, filename) => {
  const a = document.createElement("a");
  a.href = url;
  if (filename) a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
};
