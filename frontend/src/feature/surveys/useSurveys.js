import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import * as surveyApi from "./surveyApi";
import { surveyFailure } from "./surveyApi";
import { fullField } from "./surveyFields";
import { diagramToSave } from "./surveyDiagram";
import { readSlopeOpacity } from "../terrain/slopeOpacity";
import { IMPORT_ACTIVE } from "./SurveyImport";

/*
 * The LZ survey books' state (docs/LZ_SURVEYS.md): the books this person reads or edits, the one open with its surveys, and the
 * survey open in the editor, or (for a reader) on its published page.
 *
 * The open survey is held twice: `survey`, the server's copy as last saved or loaded (its revision is what the next save names in
 * If-Match), and `draft`, what is typed and drawn. A save that meets someone else's newer save is refused by the server, and the draft
 * is kept (`conflict` holds their copy) until this person chooses to save theirs over it or to load theirs. Measuring works on the
 * saved survey, so a draft is saved first; its answer is shown (`preview`) before it replaces the snapshot.
 */

/** What of a survey the editor changes. */
export const editableOf = (survey) => ({
  name: survey?.name ?? "",
  hlz_id: survey?.hlz_id ?? "",
  field: fullField(survey?.field),
  diagram: survey?.diagram ?? null,
  overrides: survey?.overrides ?? {},
});

const sameEdit = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** The labels of what someone else's save changed, against the copy this one was based on. */
export const changedBetween = (base, theirs) => {
  if (!base || !theirs) return [];
  const before = editableOf(base);
  const after = editableOf(theirs);
  const out = [];
  if (before.name !== after.name) out.push("name");
  if (before.hlz_id !== after.hlz_id) out.push("HLZ ID");
  Object.keys(after.field).forEach((key) => {
    if (before.field[key] !== after.field[key]) out.push(key);
  });
  if (!sameEdit(before.diagram, after.diagram)) out.push("map");
  if (!sameEdit(before.overrides, after.overrides)) out.push("decisions");
  if (!sameEdit(base.snapshot, theirs.snapshot)) out.push("measurements");
  if (base.status !== theirs.status) out.push("status");
  return out;
};

/**
 * The draft laid over a newer save (`theirs`), part by part against the copy it was made on (`base`): a part typed or drawn here keeps
 * what is here, any other takes theirs. Null when both changed one part differently: then the next save meets theirs, and says so.
 */
export const rebaseDraft = (base, mine, theirs) => {
  const before = editableOf(base);
  const after = editableOf(theirs);
  const out = { ...after, field: { ...after.field } };
  for (const key of ["name", "hlz_id", "diagram", "overrides"]) {
    if (sameEdit(mine[key], before[key])) continue;
    if (!sameEdit(after[key], before[key]) && !sameEdit(after[key], mine[key])) return null;
    out[key] = mine[key];
  }
  for (const key of new Set([...Object.keys(before.field), ...Object.keys(mine.field), ...Object.keys(after.field)])) {
    if (mine.field[key] === before.field[key]) continue;
    if (after.field[key] !== before.field[key] && after.field[key] !== mine.field[key]) return null;
    out.field[key] = mine.field[key];
  }
  return out;
};

const NO_LIST = { status: "idle", book: null, surveys: [], error: null };

/** How often the book's list is read again while the server measures imported surveys in the background. */
export const IMPORT_POLL_MS = 5000;

/** How often the book's PDF is asked about while the server prints it. */
export const PRINT_POLL_MS = 3000;

const NO_PRINT = { status: "idle", print: null, error: null };

export const useSurveys = ({ client = surveyApi } = {}) => {
  const [books, setBooks] = useState({ status: "idle", books: [], teams: [], canMakeForNoTeam: false, error: null });
  const [showArchivedBooks, setShowArchivedBooks] = useState(false);
  const [bookUuid, setBookUuid] = useState(null);
  const [list, setList] = useState(NO_LIST);
  const [bookPrint, setBookPrint] = useState(NO_PRINT);
  const [showArchived, setShowArchived] = useState(false);
  const [survey, setSurvey] = useState(null);
  const [reading, setReading] = useState(null);
  const [draft, setDraftState] = useState(null);
  const [conflict, setConflict] = useState(null);
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  // The latest of each, for the async steps below (a save takes a moment, and the person may type meanwhile).
  const surveyRef = useRef(null);
  const draftRef = useRef(null);
  const bookRef = useRef(null);
  const busyRef = useRef(null);
  const conflictRef = useRef(null);
  // Each look at the book's PDF is numbered, a Print too: an answer that comes once a later one was asked for is not how it stands.
  const printLook = useRef(0);
  // Each survey asked for is numbered: an answer that comes once another was asked for, or the book or survey was closed, is let go.
  const opening = useRef(0);
  const stopOpening = useCallback(() => {
    opening.current += 1;
    setBusy((current) => (current === "opening" ? null : current));
  }, []);
  surveyRef.current = survey;
  draftRef.current = draft;
  bookRef.current = bookUuid;
  busyRef.current = busy;
  conflictRef.current = conflict;

  const setDraft = useCallback((update) => {
    setDraftState((current) => {
      const next = typeof update === "function" ? update(current) : update;
      draftRef.current = next;
      return next;
    });
  }, []);

  const take = useCallback((next, { keepDraft = false } = {}) => {
    surveyRef.current = next;
    setSurvey(next);
    if (!keepDraft) setDraft(next ? editableOf(next) : null);
  }, [setDraft]);

  // -- Books --------------------------------------------------------------------

  const refreshBooks = useCallback(async (archived = showArchivedBooks) => {
    setBooks((current) => ({ ...current, status: "loading", error: null }));
    try {
      const answer = await client.listBooks({ archived });
      setBooks({ status: "ready", books: answer.books, teams: answer.teams ?? [], canMakeForNoTeam: Boolean(answer.can_make_for_no_team), error: null });
    } catch (err) {
      setBooks((current) => ({ ...current, status: "error", error: surveyFailure(err, "The survey books could not be loaded.") }));
    }
  }, [client, showArchivedBooks]);

  const toggleArchivedBooks = useCallback(() => {
    const next = !showArchivedBooks;
    setShowArchivedBooks(next);
    refreshBooks(next);
  }, [refreshBooks, showArchivedBooks]);

  /** The open book's page: its surveys. */
  const refresh = useCallback(async (archived = showArchived, uuid = bookRef.current) => {
    if (!uuid) return;
    setList((current) => ({ ...current, status: "loading", error: null }));
    try {
      const answer = await client.getBook(uuid, { archived });
      if (bookRef.current !== uuid) return;
      setList({ status: "ready", book: answer.book, surveys: answer.surveys, error: null });
    } catch (err) {
      if (bookRef.current !== uuid) return;
      setList((current) => ({ ...current, status: "error", error: surveyFailure(err, "The book could not be loaded.") }));
    }
  }, [client, showArchived]);

  const toggleArchived = useCallback(() => {
    const next = !showArchived;
    setShowArchived(next);
    refresh(next);
  }, [refresh, showArchived]);

  const openBook = useCallback((uuid) => {
    stopOpening();
    bookRef.current = uuid;
    setBookUuid(uuid);
    setList({ ...NO_LIST, status: "loading" });
    setBookPrint(NO_PRINT);
    return refresh(showArchived, uuid);
  }, [refresh, showArchived, stopOpening]);

  const closeBook = useCallback(() => {
    stopOpening();
    bookRef.current = null;
    setBookUuid(null);
    setList(NO_LIST);
    setBookPrint(NO_PRINT);
    take(null);
    setReading(null);
    refreshBooks();
  }, [refreshBooks, stopOpening, take]);

  /** Makes a book; the new book, or throws why not in words. */
  const createBook = useCallback(async ({ name, unitLine, teamId }) => {
    try {
      const made = await client.createBook({ name, unitLine, teamId });
      refreshBooks();
      return made;
    } catch (err) {
      throw new Error(surveyFailure(err, "The book could not be made."));
    }
  }, [client, refreshBooks]);

  // -- The book's PDF -------------------------------------------------------------------

  /**
   * How the open book's PDF stands, asked again. An answer that comes once another book is open, or once a later look or a Print was
   * asked for, is not how it stands. A book that has gone, or is no longer this person's to read, is not asked about again.
   */
  const refreshBookPrint = useCallback(async (uuid = bookRef.current) => {
    if (!uuid) return null;
    printLook.current += 1;
    const look = printLook.current;
    const current = () => bookRef.current === uuid && printLook.current === look;
    try {
      const answer = await client.bookPrint(uuid);
      if (current()) setBookPrint({ status: "ready", print: answer, error: null });
      return answer;
    } catch (err) {
      if (current()) {
        const gone = [403, 404].includes(err?.response?.status);
        const error = surveyFailure(err, "How the book's PDF stands could not be read.");
        setBookPrint((was) => (gone ? { status: "gone", print: null, error } : { ...was, status: "error", error }));
      }
      return null;
    }
  }, [client]);

  /** Asks for the book to be printed; how it stands, or throws why not in words. */
  const printBook = useCallback(async () => {
    const uuid = bookRef.current;
    if (!uuid) return null;
    printLook.current += 1;
    const look = printLook.current;
    try {
      const answer = await client.printBook(uuid);
      if (bookRef.current === uuid && printLook.current === look) setBookPrint({ status: "ready", print: answer, error: null });
      return answer;
    } catch (err) {
      throw new Error(surveyFailure(err, "The book could not be printed."));
    }
  }, [client]);

  /** The book's PDF as a Blob; throws why not in words. */
  const loadBookPdf = useCallback(async () => {
    try {
      return await client.fetchBookPdf(bookRef.current);
    } catch (err) {
      if (err?.response?.status === 409) refreshBookPrint();
      throw new Error(await surveyApi.blobFailure(err, "The book's PDF could not be fetched."));
    }
  }, [client, refreshBookPrint]);

  // How the PDF stands is asked when the book opens and whenever its list is read again (a survey published since changes the book).
  useEffect(() => {
    if (bookUuid && list.status === "ready") refreshBookPrint(bookUuid);
  }, [bookUuid, list.status, list.surveys, refreshBookPrint]);

  // While the server prints it, it is asked about again now and then, while the book's page is what is shown: each look once the
  // one before has answered.
  const printing = bookPrint.print?.state === "printing" && bookPrint.status !== "gone";
  useEffect(() => {
    if (!printing || !bookUuid || survey || reading) return undefined;
    let stopped = false;
    let timer = null;
    const look = async () => {
      await refreshBookPrint();
      if (!stopped) timer = setTimeout(look, PRINT_POLL_MS);
    };
    timer = setTimeout(look, PRINT_POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [printing, bookUuid, survey, reading, refreshBookPrint]);

  /** The old book's pages, from the importer's bundle, as drafts in the open book: { created, skipped, source }; throws why not in words. */
  const importPages = useCallback(async (file) => {
    const uuid = bookRef.current;
    if (!uuid) return null;
    let answer;
    try {
      answer = await client.importBook(uuid, file);
    } catch (err) {
      throw new Error(surveyFailure(err, "The bundle could not be imported."));
    }
    if (bookRef.current === uuid) refresh(showArchived, uuid);
    return answer;
  }, [client, refresh, showArchived]);

  // While the server measures imported surveys, the book's list is read again now and then, so each shows how it stands. Only while
  // the list is what is shown: a survey open in the editor or on its page has no list in view. Each read waits for the one before,
  // so a slow answer never has another sent behind it.
  const measuring = useMemo(() => list.surveys.some((row) => IMPORT_ACTIVE.includes(row.imported?.state)), [list.surveys]);
  const listShown = Boolean(bookUuid) && !survey && !reading;
  useEffect(() => {
    if (!measuring || !listShown) return undefined;
    let stopped = false;
    let timer = null;
    const look = async () => {
      await refresh();
      if (!stopped) timer = setTimeout(look, IMPORT_POLL_MS);
    };
    timer = setTimeout(look, IMPORT_POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [measuring, listShown, refresh]);

  /**
   * A newer copy of the open survey, from the import's measuring: what it wrote (the boundary, then the snapshot) comes into the
   * editor, and what was typed or drawn here meanwhile stays, laid over it. Left alone while a save or a measuring of this person's
   * is on its way (its answer is the newer), while they settle a conflict, and when both changed one part (the next save says so).
   */
  const follow = useCallback((theirs) => {
    const base = surveyRef.current;
    if (!base || !theirs?.field || theirs.uuid !== base.uuid || busyRef.current || conflictRef.current) return;
    if (theirs.revision < base.revision) return;
    if (theirs.revision === base.revision) {
      take(theirs, { keepDraft: true });
      return;
    }
    const merged = rebaseDraft(base, draftRef.current, theirs);
    if (!merged) return;
    take(theirs, { keepDraft: true });
    setDraft(merged);
  }, [setDraft, take]);

  // While the open survey's import is measured in the background, it is read again now and then (`follow`), so the editor is never
  // a revision behind the measuring, and a save does not meet it as someone else's.
  const importing = IMPORT_ACTIVE.includes(survey?.imported?.state);
  const openUuid = survey?.uuid ?? null;
  useEffect(() => {
    if (!importing || !openUuid) return undefined;
    let stopped = false;
    let timer = null;
    const look = async () => {
      try {
        const theirs = await client.getSurvey(openUuid);
        if (!stopped) follow(theirs);
      } catch {
        // The next look tries again.
      }
      if (!stopped) timer = setTimeout(look, IMPORT_POLL_MS);
    };
    timer = setTimeout(look, IMPORT_POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [client, follow, importing, openUuid]);

  /** Changes the open book's details (`patch`: name, unit_line, cover_text, page_order, team_id); throws why not in words. */
  const updateBook = useCallback(async (patch) => {
    const book = list.book;
    if (!book) return null;
    try {
      const answer = await client.updateBook(book.uuid, book.revision, patch);
      if (bookRef.current === book.uuid) {
        setList((current) => ({ ...current, book: answer }));
        // Its name, unit line and cover text are on the PDF: one printed before them is not the book now.
        refreshBookPrint(book.uuid);
      }
      return answer;
    } catch (err) {
      if (err?.response?.status === 409) refresh();
      throw new Error(surveyFailure(err, "The book could not be changed."));
    }
  }, [client, list.book, refresh, refreshBookPrint]);

  const renameBook = useCallback((name) => updateBook({ name }), [updateBook]);

  /** Archives (and closes) or restores the open book; throws why not in words. An answer that comes once another book is open is not its. */
  const setBookArchived = useCallback(async (archived) => {
    const book = list.book;
    if (!book) return null;
    let answer;
    try {
      answer = archived ? await client.archiveBook(book.uuid) : await client.restoreBook(book.uuid);
    } catch (err) {
      throw new Error(surveyFailure(err, archived ? "The book could not be archived." : "The book could not be restored."));
    }
    if (bookRef.current !== book.uuid) return answer;
    if (archived) closeBook();
    else setList((current) => ({ ...current, book: answer }));
    return answer;
  }, [client, closeBook, list.book]);

  // -- Surveys ------------------------------------------------------------------

  /** Opens a survey: in the editor for an editor, on its published page for a reader. Null when it opened, else why not, in words. */
  const openSurvey = useCallback(async (uuid) => {
    const ticket = ++opening.current;
    setBusy("opening");
    setError(null);
    try {
      const answer = await client.getSurvey(uuid);
      if (ticket !== opening.current) return null;
      if (answer.field) {
        take(answer);
        setReading(null);
      } else {
        setReading(answer);
      }
      setConflict(null);
      setPreview(null);
      return null;
    } catch (err) {
      return ticket === opening.current ? surveyFailure(err, "The survey could not be opened.") : null;
    } finally {
      if (ticket === opening.current) setBusy(null);
    }
  }, [client, take]);

  const closeSurvey = useCallback(() => {
    stopOpening();
    take(null);
    setReading(null);
    setConflict(null);
    setPreview(null);
    setError(null);
    refresh();
  }, [refresh, stopOpening, take]);

  const create = useCallback(async ({ name, hlzId }) => {
    setBusy("creating");
    try {
      const made = await client.createSurvey({ book: bookRef.current, name, hlzId, field: {}, diagram: null });
      take(made);
      setConflict(null);
      setPreview(null);
      setError(null);
      return made;
    } catch (err) {
      throw new Error(surveyFailure(err, "The survey could not be made."));
    } finally {
      setBusy(null);
    }
  }, [client, take]);

  const dirty = useMemo(() => Boolean(survey && draft && !sameEdit(draft, editableOf(survey))), [survey, draft]);

  const setField = useCallback((key, value) => setDraft((d) => (d ? { ...d, field: { ...d.field, [key]: value } } : d)), [setDraft]);
  const setName = useCallback((name) => setDraft((d) => (d ? { ...d, name } : d)), [setDraft]);
  const setHlzId = useCallback((hlzId) => setDraft((d) => (d ? { ...d, hlz_id: hlzId } : d)), [setDraft]);
  const setDiagram = useCallback(
    (update) => setDraft((d) => (d ? { ...d, diagram: typeof update === "function" ? update(d.diagram) : update } : d)),
    [setDraft],
  );

  /** Saves the draft (with `overrides` in place of the draft's, when given). The survey as saved, or null when it was not. */
  const save = useCallback(async ({ overrides } = {}) => {
    const base = surveyRef.current;
    const sent = draftRef.current;
    if (!base || !sent) return null;
    const body = {
      name: sent.name,
      hlz_id: sent.hlz_id,
      field: sent.field,
      diagram: diagramToSave(sent.diagram, sent.field),
      overrides: overrides ?? sent.overrides,
    };
    setBusy("saving");
    setError(null);
    try {
      const saved = await client.saveSurvey(base.uuid, base.revision, body);
      surveyRef.current = saved;
      setSurvey(saved);
      // Typed while the save was on its way: that stays in the draft, to go with the next save.
      setDraft((current) => (current === sent ? editableOf(saved) : current));
      setConflict(null);
      return saved;
    } catch (err) {
      if (err?.response?.status === 409 && err.response.data?.code === "revision_conflict") {
        setConflict(err.response.data.server);
        setError(null);
      } else {
        setError(surveyFailure(err, "The survey could not be saved."));
      }
      return null;
    } finally {
      setBusy(null);
    }
  }, [client, setDraft]);

  /** After a conflict: base the draft on their copy, so the next save puts what is typed here over it. */
  const keepMine = useCallback(async () => {
    if (!conflict) return null;
    take(conflict, { keepDraft: true });
    setConflict(null);
    return save();
  }, [conflict, save, take]);

  /** After a conflict: let go of what is typed here and load their copy. */
  const takeTheirs = useCallback(() => {
    if (!conflict) return;
    take(conflict);
    setConflict(null);
  }, [conflict, take]);

  const measure = useCallback(async () => {
    if (!surveyRef.current) return null;
    if (dirty && !(await save())) return null;
    setBusy("measuring");
    setError(null);
    try {
      const answer = await client.measureSurvey(surveyRef.current.uuid, { apply: false });
      setPreview(answer);
      return answer;
    } catch (err) {
      setError(surveyFailure(err, "The LZ could not be measured."));
      return null;
    } finally {
      setBusy(null);
    }
  }, [client, dirty, save]);

  const applyMeasure = useCallback(async () => {
    const base = surveyRef.current;
    if (!base) return null;
    setBusy("applying");
    setError(null);
    try {
      const answer = await client.measureSurvey(base.uuid, { apply: true, revision: base.revision });
      take(answer.survey, { keepDraft: true });
      setDraft((current) => (current ? { ...current, overrides: answer.survey.overrides } : current));
      setPreview(null);
      return answer.survey;
    } catch (err) {
      if (err?.response?.data?.code === "revision_conflict") setConflict(err.response.data.server);
      else setError(surveyFailure(err, "The measurements could not be kept."));
      return null;
    } finally {
      setBusy(null);
    }
  }, [client, setDraft, take]);

  const dismissPreview = useCallback(() => setPreview(null), []);

  /**
   * The import's notes on the open survey have been looked at. Not a change to the survey (its revision stays), so only its import
   * record is taken from the answer: the rest of this copy stays the one its next save is based on.
   */
  const markImportChecked = useCallback(async () => {
    const base = surveyRef.current;
    if (!base) return null;
    setBusy("checking");
    setError(null);
    try {
      const answer = await client.markImportChecked(base.uuid);
      const current = surveyRef.current;
      if (current?.uuid === base.uuid) {
        const next = { ...current, imported: answer.imported };
        surveyRef.current = next;
        setSurvey(next);
      }
      return answer;
    } catch (err) {
      setError(surveyFailure(err, "The notes could not be marked as checked."));
      return null;
    } finally {
      setBusy(null);
    }
  }, [client]);

  /** Settles a disagreement: `choice` "computed" (accept what EZ/PZ measured) or "field" (print the survey's, with a reason). */
  const decide = useCallback((key, choice, reason = "") => {
    const overrides = { ...(draftRef.current?.overrides ?? {}), [key]: { choice, reason } };
    return save({ overrides });
  }, [save]);

  const undecide = useCallback((key) => {
    const { [key]: _gone, ...overrides } = draftRef.current?.overrides ?? {};
    return save({ overrides });
  }, [save]);

  const setArchived = useCallback(async (archived) => {
    const base = surveyRef.current;
    if (!base) return null;
    setBusy("archiving");
    try {
      const answer = archived ? await client.archiveSurvey(base.uuid) : await client.restoreSurvey(base.uuid);
      take(answer, { keepDraft: true });
      return answer;
    } catch (err) {
      setError(surveyFailure(err, archived ? "The survey could not be archived." : "The survey could not be restored."));
      return null;
    } finally {
      setBusy(null);
    }
  }, [client, take]);

  // -- Review and publishing ------------------------------------------------------

  /** Sends the draft for review, or takes it back (`to`: "review" | "draft"); saves what is typed first. */
  const setReview = useCallback(async (to) => {
    if (dirty && !(await save())) return null;
    const base = surveyRef.current;
    if (!base) return null;
    setBusy(to === "review" ? "submitting" : "returning");
    setError(null);
    try {
      const answer = to === "review" ? await client.submitSurvey(base.uuid, base.revision) : await client.backToDraft(base.uuid, base.revision);
      take(answer);
      return answer;
    } catch (err) {
      if (err?.response?.data?.code === "revision_conflict") setConflict(err.response.data.server);
      else setError(surveyFailure(err, "The survey's status could not be changed."));
      return null;
    } finally {
      setBusy(null);
    }
  }, [client, dirty, save, take]);

  /** Signs off and publishes the survey under review. The new revision, or throws why not in words. */
  const publish = useCallback(async ({ signoff, changes }) => {
    const base = surveyRef.current;
    if (!base) return null;
    setBusy("publishing");
    try {
      const answer = await client.publishSurvey(base.uuid, base.revision, { signoff, changes, opacity: readSlopeOpacity() });
      take(answer.survey);
      return answer.revision;
    } catch (err) {
      if (err?.response?.data?.code === "revision_conflict") setConflict(err.response.data.server);
      throw new Error(surveyFailure(err, "The survey could not be published."));
    } finally {
      setBusy(null);
    }
  }, [client, take]);

  /** The page as HTML: `rev` a published revision, else (for an editor) the survey as it is saved now. Throws why not in words. */
  const loadPage = useCallback(async (uuid, { rev } = {}) => {
    try {
      return await client.fetchPage(uuid, { rev, opacity: readSlopeOpacity() });
    } catch (err) {
      throw new Error(await surveyApi.blobFailure(err, "The page could not be drawn."));
    }
  }, [client]);

  /** The page's PDF as a Blob; throws why not in words. */
  const loadPdf = useCallback(async (uuid, { rev } = {}) => {
    try {
      return await client.fetchPdf(uuid, { rev, opacity: readSlopeOpacity() });
    } catch (err) {
      throw new Error(await surveyApi.blobFailure(err, "The PDF could not be made."));
    }
  }, [client]);

  /**
   * Photos change the survey's list of assets as they come. On a survey under review or published a change also makes it a draft
   * again, with a new revision (`moved`): this copy takes it only if it held the revision the change was made on, else someone else's
   * save came between, and the next save here meets it as a conflict. Nothing lands on a survey other than the one it was made on.
   */
  const withAssets = useCallback((uuid, update, moved) => {
    const current = surveyRef.current;
    if (!current || current.uuid !== uuid) return;
    const assets = update(current.assets ?? []);
    let next = { ...current, assets, asset_ids: assets.map((a) => a.id) };
    if (moved && moved.was === current.revision && moved.revision !== current.revision) {
      next = { ...next, status: moved.status, revision: moved.revision };
    }
    surveyRef.current = next;
    setSurvey(next);
  }, []);

  const uploadPhoto = useCallback(async (file, meta) => {
    const uuid = surveyRef.current.uuid;
    const { survey: moved, ...asset } = await client.uploadAsset(uuid, file, meta);
    withAssets(uuid, (assets) => [...assets, asset], moved);
    return asset;
  }, [client, withAssets]);

  const updatePhoto = useCallback(async (id, meta) => {
    const uuid = surveyRef.current.uuid;
    const { survey: moved, ...asset } = await client.updateAsset(uuid, id, meta);
    withAssets(uuid, (assets) => assets.map((a) => (a.id === id ? asset : a)), moved);
    return asset;
  }, [client, withAssets]);

  const removePhoto = useCallback(async (id) => {
    const uuid = surveyRef.current.uuid;
    const answer = await client.removeAsset(uuid, id);
    withAssets(uuid, (assets) => assets.filter((a) => a.id !== id), answer?.survey);
  }, [client, withAssets]);

  return {
    books,
    showArchivedBooks,
    toggleArchivedBooks,
    refreshBooks,
    bookUuid,
    openBook,
    closeBook,
    createBook,
    updateBook,
    renameBook,
    setBookArchived,
    importPages,
    bookPrint,
    refreshBookPrint,
    printBook,
    loadBookPdf,
    list,
    showArchived,
    toggleArchived,
    refresh,
    survey,
    reading,
    draft,
    dirty,
    busy,
    error,
    clearError: () => setError(null),
    conflict,
    conflictChanges: changedBetween(survey, conflict),
    keepMine,
    takeTheirs,
    preview,
    openSurvey,
    closeSurvey,
    create,
    setField,
    setName,
    setHlzId,
    setDiagram,
    save,
    measure,
    applyMeasure,
    dismissPreview,
    markImportChecked,
    decide,
    undecide,
    setArchived,
    setReview,
    publish,
    loadPage,
    loadPdf,
    uploadPhoto,
    updatePhoto,
    removePhoto,
  };
};

export default useSurveys;
