/** Hands a PDF's bytes to the browser to save, named for the document (what a file system or a chat app trips over made a space). */
export const savePdf = (bytes, title) => {
  // eslint-disable-next-line no-control-regex
  const safe = (title || "").replace(/[\u0000-\u001f\u007f\\/:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim();
  const url = URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `${safe || "document"}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

export default savePdf;
