import React, { useEffect, useRef, useState } from "react";
import Dialog, { ConfirmDialog } from "../ui/Dialog";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import useSurveys from "./useSurveys";
import BooksPage, { NewBookDialog } from "./BooksPage";
import SurveyBookPage, { BookSettingsDialog, NewSurveyDialog } from "./SurveyBookPage";
import SurveyEditor from "./SurveyEditor";
import SurveyReader from "./SurveyReader";
import { ImportDialog } from "./SurveyImport";
import "./surveys.css";

/*
 * The LZ survey books (docs/LZ_SURVEYS.md), opened from the account menu by a site admin or anyone with LZ surveys: the books this
 * person reads or edits, one book's pages, and a survey open in its editor (the book's editors) or on its published page (a
 * reader), in one window nearly the size of the screen. What each person may do comes from the server with each book. Leaving a
 * survey with changes not saved asks first, as closing an LZ/PZ does: Cancel, Don't save, or Save.
 */

const lzCount = (n) => `${n} ${n === 1 ? "LZ" : "LZs"}`;

const SurveyBook = ({ onClose, client, isAdmin = false }) => {
  const surveys = useSurveys(client ? { client } : undefined);
  const { show } = useToast();
  const [making, setMaking] = useState(null); // "book" | "survey" | "import"
  const [settings, setSettings] = useState(false);
  const [leaving, setLeaving] = useState(null); // what to do once the question is answered: "book" | "books" | "close"
  const [archiving, setArchiving] = useState(null); // "survey" | "book"
  const { survey, reading, dirty, busy, list, bookUuid } = surveys;

  useEffect(() => {
    surveys.refreshBooks();
    // Once, when the window opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Ctrl/⌘ S saves the open survey, as it saves an LZ/PZ on the planning map (which stands down while a dialog is open). Not
  // while another dialog is over this one.
  const saveRef = useRef(null);
  useEffect(() => {
    const onKey = (event) => {
      if (!(event.ctrlKey || event.metaKey) || event.shiftKey || event.altKey || event.key.toLowerCase() !== "s") return;
      event.preventDefault();
      if (document.querySelectorAll('[role="dialog"]').length > 1) return;
      saveRef.current?.();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const leave = (where) => {
    if (where === "close") onClose();
    else if (where === "books") surveys.closeBook();
    else surveys.closeSurvey();
  };
  const askToLeave = (where) => (survey && dirty ? setLeaving(where) : leave(where));

  const save = async () => {
    const saved = await surveys.save();
    if (saved) show({ message: `Saved ${saved.name}, revision ${saved.revision}.` });
    return saved;
  };
  saveRef.current = survey && dirty && !busy && !survey.archived ? save : null;

  const open = async (uuid) => {
    const failure = await surveys.openSurvey(uuid);
    if (failure) show({ message: failure, tone: "error" });
  };

  const book = list.book;
  let title = "LZ survey books";
  let note = "Books you read or edit";
  if (survey) {
    title = `${survey.hlz_id ? `${survey.hlz_id} · ` : ""}${survey.name}`;
    note = dirty ? "Changes not saved." : `Saved · revision ${survey.revision}`;
  } else if (reading) {
    title = `${reading.hlz_id ? `${reading.hlz_id} · ` : ""}${reading.name}`;
    note = `Published · REV ${reading.published_revision}`;
  } else if (bookUuid) {
    title = book?.name || "Survey book";
    note = book?.can_edit
      ? `${lzCount(list.surveys.filter((s) => !s.archived).length)} · drafts are its editors' alone until published`
      : `${lzCount(list.surveys.length)} published`;
  }

  let footer = null;
  if (survey) {
    footer = (
      <>
        <button type="button" className="ui-btn ui-btn--38" onClick={() => askToLeave("book")}>
          <Icon name="chevronLeft" size={14} />
          Book
        </button>
        {survey.archived ? (
          <button type="button" className="ui-btn ui-btn--38" disabled={Boolean(busy)} onClick={() => surveys.setArchived(false)}>Restore</button>
        ) : (
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" disabled={Boolean(busy)} onClick={() => setArchiving("survey")}>Archive</button>
        )}
        <button type="button" className="ui-btn ui-btn--primary ui-btn--38" disabled={!dirty || Boolean(busy) || survey.archived} onClick={save}>
          <Icon name="save" size={14} />
          {busy === "saving" ? "Saving…" : "Save"}
        </button>
      </>
    );
  } else if (reading) {
    footer = (
      <button type="button" className="ui-btn ui-btn--38" onClick={() => surveys.closeSurvey()}>
        <Icon name="chevronLeft" size={14} />
        Book
      </button>
    );
  } else if (bookUuid) {
    footer = (
      <>
        <button type="button" className="ui-btn ui-btn--38" onClick={() => surveys.closeBook()}>
          <Icon name="chevronLeft" size={14} />
          Books
        </button>
        {book?.can_edit && !book.archived && (
          <>
            <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={() => setSettings(true)}>
              <Icon name="pencil" size={14} />
              The book
            </button>
            <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={() => setArchiving("book")}>Archive the book</button>
          </>
        )}
        {book?.archived && isAdmin && (
          <button type="button" className="ui-btn ui-btn--38" onClick={async () => {
            try {
              await surveys.setBookArchived(false);
              show({ message: `${book.name} restored.` });
            } catch (err) {
              show({ message: err.message, tone: "error" });
            }
          }}>Restore the book</button>
        )}
      </>
    );
  }

  let body;
  if (survey) body = <SurveyEditor surveys={surveys} />;
  else if (reading) body = <SurveyReader key={reading.uuid} surveys={surveys} />;
  else if (bookUuid) {
    body = <SurveyBookPage surveys={surveys} onNew={() => setMaking("survey")} onImport={() => setMaking("import")} onOpen={open} />;
  }
  else body = <BooksPage surveys={surveys} isAdmin={isAdmin} onNew={() => setMaking("book")} onOpen={(uuid) => surveys.openBook(uuid)} />;

  return (
    <>
      <Dialog
        title={title}
        subtitle={survey || reading ? "LZ survey" : bookUuid ? "Survey book" : "LZ surveys"}
        icon="hexagon"
        className="survey-window"
        onClose={() => askToLeave("close")}
        note={note}
        footer={footer}
      >
        {body}
      </Dialog>
      {making === "book" && (
        <NewBookDialog
          teams={surveys.books.teams}
          canMakeForNoTeam={surveys.books.canMakeForNoTeam}
          onCancel={() => setMaking(null)}
          onCreate={async (values) => {
            const made = await surveys.createBook(values);
            setMaking(null);
            show({ message: `${made.name} made.` });
            surveys.openBook(made.uuid);
          }}
        />
      )}
      {making === "import" && book && (
        <ImportDialog bookName={book.name} onImport={surveys.importPages} onCancel={() => setMaking(null)} />
      )}
      {making === "survey" && (
        <NewSurveyDialog
          onCancel={() => setMaking(null)}
          onCreate={async (values) => {
            await surveys.create(values);
            setMaking(null);
          }}
        />
      )}
      {settings && book && (
        <BookSettingsDialog
          book={book}
          teams={surveys.books.teams}
          canChooseTeam={isAdmin}
          onCancel={() => setSettings(false)}
          onSave={async (patch) => {
            await surveys.updateBook(patch);
            setSettings(false);
            show({ message: "The book is saved." });
          }}
        />
      )}
      {archiving === "survey" && (
        <ConfirmDialog
          title={`Archive ${survey?.name ?? "this survey"}?`}
          text="It leaves the book but is kept, with everything in it, and can be restored. A survey is never deleted: a printed book may hold it."
          confirmLabel="Archive"
          onCancel={() => setArchiving(null)}
          onConfirm={async () => {
            setArchiving(null);
            if (await surveys.setArchived(true)) show({ message: `${survey.name} archived.` });
          }}
        />
      )}
      {archiving === "book" && book && (
        <ConfirmDialog
          title={`Archive ${book.name}?`}
          text="Its readers no longer see it, and only a site admin can restore it. Nothing in it is deleted: a printed book may hold its pages."
          confirmLabel="Archive"
          onCancel={() => setArchiving(null)}
          onConfirm={async () => {
            setArchiving(null);
            try {
              await surveys.setBookArchived(true);
              show({ message: `${book.name} archived.` });
            } catch (err) {
              show({ message: err.message, tone: "error" });
            }
          }}
        />
      )}
      {leaving && (
        <ConfirmDialog
          title="Save your changes to this survey?"
          text="They are not saved yet. Without saving, they are lost."
          confirmLabel="Save"
          confirmIcon="save"
          secondary={{ label: "Don't save", onClick: () => { const where = leaving; setLeaving(null); leave(where); } }}
          onCancel={() => setLeaving(null)}
          onConfirm={async () => {
            const where = leaving;
            setLeaving(null);
            if (await save()) leave(where);
          }}
        />
      )}
    </>
  );
};

export default SurveyBook;
