import api from "../auth/api";
import { exportFileName, pointsToGpx } from "./customPoints";

const download = (blob, fileName) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // After the click has been taken up: revoked at once, some browsers save nothing.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

/** Points as a GPX, worked out here: nothing about them goes anywhere. */
export const exportPointsGpx = (name, points) => {
  download(new Blob([pointsToGpx(name, points)], { type: "application/gpx+xml" }), exportFileName(name, "gpx"));
};

/** Points as an AMPS .LPS, written by the server from its template, as a .ths is. It keeps nothing. */
export const exportPointsLps = async (name, points) => {
  const res = await api.post("/points-lps", { name, points }, { responseType: "blob" });
  download(res.data, exportFileName(name, "LPS"));
};
