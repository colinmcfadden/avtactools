import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Draggable from "react-draggable";
import Icon from "../ui/Icon";
import Spinner from "../ui/Spinner";
import { findInDocument } from "./documentSearch";
import { openPdf } from "./pdfLoader";
import { savePdf } from "./savePdf";
import "../export/ExportModal.css";
import "./documents.css";

/*
 * A document read as a PDF (docs/DOCUMENTS.md §8.1): a window over the map, moved by its bar and resized from its corner, so the OPORD
 * stays open beside the plan; where it was and its size are remembered in this browser. At 850 px and below it is the whole screen.
 * Static: pages drawn on canvases as they come near the view (the rest let go of), zoom, page n of N, and find in the document over the
 * server's page text (documentSearch.js). No link is followed and nothing in the PDF runs (pdfLoader.js).
 *
 * `loadPdf` gives the PDF's bytes and `loadText` its page text (`{ pages }`, or null for a scan); both are the caller's, so a Library
 * document and a pack's are read the same way.
 */

const PLACE_KEY = "ezpz.documentViewer";
const NARROW = "(max-width: 850px)";
const ZOOMS = [0.5, 0.67, 0.8, 1, 1.25, 1.5, 2, 3];
const GAP = 12;
// Pages this far either side of the view are drawn; further ones are let go of.
const NEAR = "150% 0px";

const readPlace = () => {
  try {
    const place = JSON.parse(window.localStorage.getItem(PLACE_KEY) || "null");
    if (place && [place.x, place.y, place.width, place.height].every(Number.isFinite)) return place;
  } catch {
    // Storage that cannot be read is no remembered place.
  }
  return null;
};

// How much of the window must stay in the browser's: its title bar, to drag it by, and enough of it to see.
const KEEP_IN_VIEW = 120;
const BAR = 48;

/**
 * A remembered place made to fit the browser it opens in: no larger than it, and its title bar inside it, so a window left low or wide
 * on a large screen opens on a laptop where it can still be dragged and closed. `x` and `y` move the window from where it stands
 * unmoved (.export-modal-container: centred across, 5vh down).
 */
export const fitPlace = (place, view) => {
  if (!place || !view?.width || !view?.height) return place;
  const width = Math.min(place.width, Math.max(360, view.width - 32));
  const height = Math.min(place.height, Math.max(320, view.height - 80));
  const left = (view.width - width) / 2;
  const top = view.height * 0.05;
  const x = Math.min(Math.max(place.x, KEEP_IN_VIEW - width - left), view.width - KEEP_IN_VIEW - left);
  const y = Math.min(Math.max(place.y, -top), view.height - BAR - top);
  return { x: Math.round(x), y: Math.round(y), width, height };
};

const keepPlace = (place) => {
  try {
    window.localStorage.setItem(PLACE_KEY, JSON.stringify(place));
  } catch {
    // A browser that keeps nothing still shows the window.
  }
};

const useNarrow = () => {
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && Boolean(window.matchMedia?.(NARROW).matches));
  useEffect(() => {
    const query = window.matchMedia?.(NARROW);
    if (!query) return undefined;
    const changed = () => setNarrow(query.matches);
    query.addEventListener?.("change", changed);
    return () => query.removeEventListener?.("change", changed);
  }, []);
  return narrow;
};

/** One page: its box always (so the scroll is right), its drawing only while it is near the view. */
const PdfPage = ({ doc, number, width, ratio, near, registerRef }) => {
  const canvasRef = useRef(null);
  const [drawn, setDrawn] = useState(false);
  const [pageRatio, setPageRatio] = useState(ratio);
  const height = Math.round(width * pageRatio);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!near || !doc || !canvas || width <= 0) {
      if (canvas && !near) {
        // Let go of a page out of sight: a canvas holds its pixels until it is resized.
        canvas.width = 0;
        canvas.height = 0;
        setDrawn(false);
      }
      return undefined;
    }
    let task = null;
    let gone = false;
    doc
      .getPage(number)
      .then((page) => {
        if (gone) return;
        const natural = page.getViewport({ scale: 1 });
        setPageRatio(natural.height / natural.width);
        const viewport = page.getViewport({ scale: width / natural.width });
        const dpr = Math.min(window.devicePixelRatio || 1, 3);
        canvas.width = Math.floor(viewport.width * dpr);
        canvas.height = Math.floor(viewport.height * dpr);
        const context = canvas.getContext("2d");
        if (!context) return;
        task = page.render({ canvasContext: context, viewport, transform: dpr === 1 ? null : [dpr, 0, 0, dpr, 0, 0] });
        return task.promise.then(() => !gone && setDrawn(true));
      })
      .catch(() => {
        // A page that cannot be drawn (or a drawing cancelled) stays blank; the rest of the document still reads.
      });
    return () => {
      gone = true;
      task?.cancel?.();
    };
  }, [doc, number, width, near]);

  return (
    <div
      ref={(el) => registerRef(number, el)}
      data-page={number}
      className="document-viewer__page"
      style={{ width, height }}
      role="img"
      aria-label={`Page ${number}`}
    >
      <canvas ref={canvasRef} style={{ width, height }} />
      {!drawn && near && <Spinner size={18} className="document-viewer__page-spinner" />}
    </div>
  );
};

const DocumentViewer = ({ title, detail, loadPdf, loadText, onClose }) => {
  const narrow = useNarrow();
  const windowRef = useRef(null);
  const scrollRef = useRef(null);
  const findRef = useRef(null);
  const pageEls = useRef(new Map());
  const bytesRef = useRef(null);
  const [place] = useState(() => fitPlace(readPlace(), typeof window === "undefined" ? null : { width: window.innerWidth, height: window.innerHeight }));
  const [status, setStatus] = useState("loading");
  const [doc, setDoc] = useState(null);
  const [count, setCount] = useState(0);
  const [ratio, setRatio] = useState(11 / 8.5);
  const [zoom, setZoom] = useState(1);
  const [fitWidth, setFitWidth] = useState(0);
  const [current, setCurrent] = useState(1);
  const [near, setNear] = useState(() => new Set([1, 2, 3]));
  const [text, setText] = useState({ status: "loading", pages: [] });
  const [query, setQuery] = useState("");
  const [hit, setHit] = useState(-1);

  // The PDF and its text, read once.
  useEffect(() => {
    let gone = false;
    let opened = null;
    (async () => {
      try {
        const bytes = await loadPdf();
        if (gone) return;
        bytesRef.current = bytes;
        opened = await openPdf(bytes);
        // Closed while pdf.js loaded: the clean-up below ran before there was anything to let go of.
        if (gone) {
          opened.destroy?.();
          return;
        }
        const pdf = await opened.promise;
        if (gone) return;
        const first = await pdf.getPage(1);
        const natural = first.getViewport({ scale: 1 });
        if (gone) return;
        setRatio(natural.height / natural.width);
        setCount(pdf.numPages);
        setDoc(pdf);
        setStatus("ready");
      } catch {
        if (!gone) setStatus("failed");
      }
    })();
    (async () => {
      try {
        const found = await loadText();
        if (!gone) setText(found?.pages ? { status: "ready", pages: found.pages, truncated: Boolean(found.truncated) } : { status: "none", pages: [] });
      } catch (error) {
        if (!gone) setText({ status: error?.response?.data?.code === "no_text" ? "none" : "failed", pages: [] });
      }
    })();
    return () => {
      gone = true;
      opened?.destroy?.();
    };
    // Read once for the document the viewer opened on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The page width that fits the window, followed as it is resized.
  useEffect(() => {
    const scroller = scrollRef.current;
    if (!scroller) return undefined;
    const measure = () => setFitWidth(Math.max(0, scroller.clientWidth - 2 * GAP));
    measure();
    if (typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(scroller);
    return () => observer.disconnect();
  }, [status]);

  // Its size, kept as the person leaves it.
  useEffect(() => {
    const node = windowRef.current;
    if (!node || narrow || typeof ResizeObserver === "undefined") return undefined;
    let timer = null;
    const observer = new ResizeObserver(() => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        const at = readPlace() ?? { x: 0, y: 0 };
        keepPlace({ ...at, width: node.offsetWidth, height: node.offsetHeight });
      }, 300);
    });
    observer.observe(node);
    return () => {
      clearTimeout(timer);
      observer.disconnect();
    };
  }, [narrow]);

  const registerRef = useCallback((number, el) => {
    if (el) pageEls.current.set(number, el);
    else pageEls.current.delete(number);
  }, []);

  // Which pages are near the view: drawn, and the rest let go of.
  useEffect(() => {
    const scroller = scrollRef.current;
    if (!scroller || !count) return undefined;
    if (typeof IntersectionObserver === "undefined") {
      setNear(new Set([current - 1, current, current + 1, current + 2].filter((n) => n >= 1 && n <= count)));
      return undefined;
    }
    const observer = new IntersectionObserver(
      (entries) =>
        setNear((before) => {
          const next = new Set(before);
          entries.forEach((entry) => {
            const number = Number(entry.target.dataset.page);
            if (entry.isIntersecting) next.add(number);
            else next.delete(number);
          });
          return next;
        }),
      { root: scroller, rootMargin: NEAR },
    );
    pageEls.current.forEach((el) => observer.observe(el));
    return () => observer.disconnect();
    // `current` matters only where there is no IntersectionObserver.
  }, [count, zoom, fitWidth, current]);

  const onScroll = () => {
    const scroller = scrollRef.current;
    if (!scroller) return;
    const line = scroller.scrollTop + scroller.clientHeight / 3;
    let page = 1;
    pageEls.current.forEach((el, number) => {
      if (el.offsetTop <= line && number > page) page = number;
    });
    setCurrent(page);
  };

  const goTo = (number) => {
    const page = Math.min(Math.max(1, number), count || 1);
    const el = pageEls.current.get(page);
    const scroller = scrollRef.current;
    if (el && scroller) scroller.scrollTop = el.offsetTop - GAP;
    setCurrent(page);
  };

  const found = useMemo(() => (text.status === "ready" ? findInDocument(text.pages, query) : { hits: [], count: 0, more: false }), [text, query]);
  // A new search shows its first match, as a browser's find does, so the next step (Enter) goes on to the second.
  useEffect(() => {
    setHit(found.hits.length ? 0 : -1);
    if (found.hits.length) goTo(found.hits[0].page);
    // goTo reads the pages laid out now; what moves this is the search's answer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [found]);

  const step = (by) => {
    if (!found.hits.length) return;
    const next = (hit + by + found.hits.length) % found.hits.length;
    setHit(next);
    goTo(found.hits[next].page);
  };

  const download = () => {
    if (bytesRef.current) savePdf(bytesRef.current, title);
  };

  const onKeyDown = (event) => {
    if (event.key === "Escape") {
      if (event.target === findRef.current && query) {
        setQuery("");
      } else {
        onClose();
      }
      event.stopPropagation();
    } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f" && text.status === "ready") {
      event.preventDefault();
      findRef.current?.focus();
    }
  };

  const width = Math.round(fitWidth * zoom);
  const zoomAt = ZOOMS.indexOf(zoom);
  const findWords =
    text.status === "loading"
      ? "Reading the text…"
      : text.status === "none"
        ? "No text to search (a scan?)"
        : text.status === "failed"
          ? "The text could not be loaded"
          : !query.trim()
            ? text.truncated ? "Only the first pages' text was read" : ""
            : found.count === 0
              ? "Not found"
              : `${hit + 1} of ${found.count.toLocaleString("en-US")}${found.more ? " (the first 1,000 listed)" : ""}`;

  const frame = (
    <div
      ref={windowRef}
      className={`export-modal-container glass-panel document-viewer${narrow ? " document-viewer--full" : ""}`}
      role="dialog"
      aria-label={`Document: ${title}`}
      style={!narrow && place ? { width: place.width, height: place.height } : undefined}
      onKeyDown={onKeyDown}
    >
      <div className="modal-header document-viewer__bar">
        <Icon name="file" size={16} />
        <div className="document-viewer__title">
          <div className="document-viewer__name">{title}</div>
          {detail && <div className="document-viewer__detail">{detail}</div>}
        </div>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Download PDF" title="Download PDF" disabled={status !== "ready"} onClick={download}>
          <Icon name="download" size={16} />
        </button>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Close" title="Close" onClick={onClose}>
          <Icon name="x" size={16} />
        </button>
      </div>
      <div className="document-viewer__tools">
        <div className="document-viewer__find">
          <Icon name="search" size={14} />
          <input
            ref={findRef}
            type="search"
            className="ui-input document-viewer__find-input"
            placeholder="Find in the document"
            aria-label="Find in the document"
            disabled={text.status !== "ready"}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                step(event.shiftKey ? -1 : 1);
              }
            }}
          />
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Previous match" disabled={!found.hits.length} onClick={() => step(-1)}>
            <Icon name="chevronUp" size={15} />
          </button>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Next match" disabled={!found.hits.length} onClick={() => step(1)}>
            <Icon name="chevronDown" size={15} />
          </button>
        </div>
        <div className="document-viewer__zoom">
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Zoom out" disabled={zoomAt <= 0} onClick={() => setZoom(ZOOMS[zoomAt - 1])}>
            <Icon name="minus" size={15} />
          </button>
          <span aria-live="polite">{Math.round(zoom * 100)}%</span>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--30 ui-btn--square" aria-label="Zoom in" disabled={zoomAt >= ZOOMS.length - 1} onClick={() => setZoom(ZOOMS[zoomAt + 1])}>
            <Icon name="plus" size={15} />
          </button>
        </div>
        <span className="document-viewer__pages">{count ? `Page ${current} of ${count}` : ""}</span>
      </div>
      {(findWords || (query.trim() && found.hits.length > 0)) && (
        <div className="document-viewer__found">
          <div className="document-viewer__found-words" role="status">{findWords}</div>
          {query.trim() && found.hits.length > 0 && (
            <ol className="document-viewer__hits" aria-label="Where it is found">
              {found.hits.map((h, i) => (
                <li key={`${h.page}:${h.start}`}>
                  <button
                    type="button"
                    className={`document-viewer__hit${i === hit ? " document-viewer__hit--current" : ""}`}
                    aria-current={i === hit ? "true" : undefined}
                    onClick={() => {
                      setHit(i);
                      goTo(h.page);
                    }}
                  >
                    <span className="document-viewer__hit-page">p. {h.page}</span>
                    <span className="document-viewer__hit-text">
                      {h.cutBefore ? "…" : ""}
                      {h.before}
                      <mark>{h.match}</mark>
                      {h.after}
                      {h.cutAfter ? "…" : ""}
                    </span>
                  </button>
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
      <div ref={scrollRef} className="document-viewer__scroll" onScroll={onScroll}>
        {status === "loading" && (
          <div className="document-viewer__state">
            <Spinner size={18} /> Opening the document…
          </div>
        )}
        {status === "failed" && <div className="document-viewer__state" role="alert">This document could not be opened. Check the connection and try again.</div>}
        {status === "ready" &&
          Array.from({ length: count }, (_, i) => (
            <PdfPage key={i + 1} doc={doc} number={i + 1} width={width} ratio={ratio} near={near.has(i + 1)} registerRef={registerRef} />
          ))}
      </div>
    </div>
  );

  if (narrow) return frame;
  return (
    <Draggable
      nodeRef={windowRef}
      handle=".document-viewer__bar"
      cancel="button"
      defaultPosition={place ? { x: place.x, y: place.y } : undefined}
      onStop={(_event, at) => {
        const node = windowRef.current;
        keepPlace({ x: at.x, y: at.y, width: node?.offsetWidth ?? 640, height: node?.offsetHeight ?? 720 });
      }}
    >
      {frame}
    </Draggable>
  );
};

export default DocumentViewer;
