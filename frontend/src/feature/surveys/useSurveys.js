import { useCallback, useMemo, useRef, useState } from "react";
import * as surveyApi from "./surveyApi";
import { surveyFailure } from "./surveyApi";
import { fullField } from "./surveyFields";
import { diagramToSave } from "./surveyDiagram";
import { readSlopeOpacity } from "../terrain/slopeOpacity";

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

const NO_LIST = { status: "idle", book: null, surveys: [], error: null };

export const useSurveys = ({ client = surveyApi } = {}) => {
  const [books, setBooks] = useState({ status: "idle", books: [], teams: [], canMakeForNoTeam: false, error: null });
  const [showArchivedBooks, setShowArchivedBooks] = useState(false);
  const [bookUuid, setBookUuid] = useState(null);
  const [list, setList] = useState(NO_LIST);
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
  surveyRef.current = survey;
  draftRef.current = draft;
  bookRef.current = bookUuid;

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
    bookRef.current = uuid;
    setBookUuid(uuid);
    setList({ ...NO_LIST, status: "loading" });
    return refresh(showArchived, uuid);
  }, [refresh, showArchived]);

  const closeBook = useCallback(() => {
    bookRef.current = null;
    setBookUuid(null);
    setList(NO_LIST);
    take(null);
    setReading(null);
    refreshBooks();
  }, [refreshBooks, take]);

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

  /** Changes the open book's details (`patch`: name, unit_line, cover_text, page_order, team_id); throws why not in words. */
  const updateBook = useCallback(async (patch) => {
    const book = list.book;
    if (!book) return null;
    try {
      const answer = await client.updateBook(book.uuid, book.revision, patch);
      setList((current) => ({ ...current, book: answer }));
      return answer;
    } catch (err) {
      if (err?.response?.status === 409) refresh();
      throw new Error(surveyFailure(err, "The book could not be changed."));
    }
  }, [client, list.book, refresh]);

  const renameBook = useCallback((name) => updateBook({ name }), [updateBook]);

  const setBookArchived = useCallback(async (archived) => {
    const book = list.book;
    if (!book) return null;
    try {
      const answer = archived ? await client.archiveBook(book.uuid) : await client.restoreBook(book.uuid);
      setList((current) => ({ ...current, book: answer }));
      return answer;
    } catch (err) {
      throw new Error(surveyFailure(err, archived ? "The book could not be archived." : "The book could not be restored."));
    }
  }, [client, list.book]);

  // -- Surveys ------------------------------------------------------------------

  /** Opens a survey: in the editor for an editor, on its published page for a reader. Null when it opened, else why not, in words. */
  const openSurvey = useCallback(async (uuid) => {
    setBusy("opening");
    setError(null);
    try {
      const answer = await client.getSurvey(uuid);
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
      return surveyFailure(err, "The survey could not be opened.");
    } finally {
      setBusy(null);
    }
  }, [client, take]);

  const closeSurvey = useCallback(() => {
    take(null);
    setReading(null);
    setConflict(null);
    setPreview(null);
    setError(null);
    refresh();
  }, [refresh, take]);

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

  // Photos change the survey's list of assets, not its revision: they are kept as they come.
  const withAssets = useCallback((update) => {
    const current = surveyRef.current;
    if (!current) return;
    const assets = update(current.assets ?? []);
    const next = { ...current, assets, asset_ids: assets.map((a) => a.id) };
    surveyRef.current = next;
    setSurvey(next);
  }, []);

  const uploadPhoto = useCallback(async (file, meta) => {
    const base = surveyRef.current;
    const asset = await client.uploadAsset(base.uuid, file, meta);
    withAssets((assets) => [...assets, asset]);
    return asset;
  }, [client, withAssets]);

  const updatePhoto = useCallback(async (id, meta) => {
    const asset = await client.updateAsset(surveyRef.current.uuid, id, meta);
    withAssets((assets) => assets.map((a) => (a.id === id ? asset : a)));
    return asset;
  }, [client, withAssets]);

  const removePhoto = useCallback(async (id) => {
    await client.removeAsset(surveyRef.current.uuid, id);
    withAssets((assets) => assets.filter((a) => a.id !== id));
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
