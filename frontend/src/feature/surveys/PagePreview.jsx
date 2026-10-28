import React, { useEffect, useId, useRef, useState } from "react";
import Icon from "../ui/Icon";

/*
 * A survey's printed page (docs/LZ_SURVEYS.md §5) as the server lays it out: shown here in a frame that runs nothing, so it can be
 * read with or without Gotenberg, and its PDF downloaded where the server prints one. The frame is the page at its own size (8.5 by
 * 11 inches), scaled to the panel.
 */

export const PAGE_W = 816;
export const PAGE_H = 1056;

/** Saves a Blob as a file, through a link the page clicks for the person. */
export const saveBlob = (blob, fileName) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
};

const PagePreview = ({ title, load, loadPdf, fileName, autoLoad = false, stale = false, heading = "Page", note }) => {
  const headingId = useId();
  const [html, setHtml] = useState(null);
  const [status, setStatus] = useState("idle");
  const [error, setError] = useState(null);
  const [printing, setPrinting] = useState(false);
  const [pdfError, setPdfError] = useState(null);
  const [scale, setScale] = useState(0.62);
  const box = useRef(null);
  const loadRef = useRef(load);
  loadRef.current = load;
  // Each drawing is numbered: only the latest one's answer is shown, so a page asked for before another revision was chosen never
  // shows under that revision's heading.
  const drawing = useRef(0);

  const draw = async () => {
    const ticket = ++drawing.current;
    setStatus("loading");
    setError(null);
    try {
      const page = await loadRef.current();
      if (ticket !== drawing.current) return;
      setHtml(page);
      setStatus("ready");
    } catch (err) {
      if (ticket !== drawing.current) return;
      setError(err?.message || "The page could not be drawn.");
      setStatus("error");
    }
  };

  useEffect(() => {
    if (autoLoad) draw();
    // A reader's page is drawn as it opens, and again when another revision is chosen (a new `load`).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoLoad, load]);

  useEffect(() => {
    const element = box.current;
    if (!element || typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(([entry]) => {
      const width = entry.contentRect.width;
      if (width > 0) setScale(Math.min(1, width / PAGE_W));
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [html]);

  const download = async () => {
    setPrinting(true);
    setPdfError(null);
    try {
      saveBlob(await loadPdf(), fileName);
    } catch (err) {
      // Said beside the button, where it stays: a toast would go before it is read.
      setPdfError(err?.message || "The PDF could not be made.");
    } finally {
      setPrinting(false);
    }
  };

  return (
    <section className="survey-panel" aria-labelledby={headingId}>
      <h3 id={headingId} className="survey-panel__title">{heading}</h3>
      {note && <p className="survey-muted">{note}</p>}
      <div className="survey-page-bar">
        {!autoLoad && (
          <button type="button" className="ui-btn ui-btn--34" onClick={draw} disabled={status === "loading"}>
            <Icon name="eye" size={14} />
            {status === "loading" ? "Drawing the page…" : html ? "Draw it again" : "Show the page"}
          </button>
        )}
        <button type="button" className="ui-btn ui-btn--primary ui-btn--34" onClick={download} disabled={printing}>
          <Icon name="download" size={14} />
          {printing ? "Printing…" : "Download PDF"}
        </button>
      </div>
      {pdfError && <p className="survey-muted" role="status">{pdfError}</p>}
      {stale && html && <p className="survey-muted" role="status">Changed since it was drawn: draw it again to see the change.</p>}
      {status === "loading" && autoLoad && <p className="survey-muted" role="status">Drawing the page…</p>}
      {error && (
        <div className="survey-banner survey-banner--error" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>{error}</div>
          <button type="button" className="ui-btn ui-btn--30" onClick={draw}>Try again</button>
        </div>
      )}
      {html && (
        <div className="survey-page-frame" ref={box} style={{ height: Math.round(PAGE_H * scale) }}>
          <iframe
            title={title}
            sandbox=""
            srcDoc={html}
            style={{ width: PAGE_W, height: PAGE_H, transform: `scale(${scale})`, transformOrigin: "0 0" }}
          />
        </div>
      )}
    </section>
  );
};

export default PagePreview;
