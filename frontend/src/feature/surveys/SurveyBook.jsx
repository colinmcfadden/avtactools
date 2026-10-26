import React, { useEffect, useRef, useState } from "react";
import Dialog, { ConfirmDialog } from "../ui/Dialog";
import Icon from "../ui/Icon";
import NameDialog from "../ui/NameDialog";
import { useToast } from "../ui/Toast";
import useSurveys from "./useSurveys";
import SurveyBookPage, { NewSurveyDialog } from "./SurveyBookPage";
import SurveyEditor from "./SurveyEditor";
import "./surveys.css";

/*
 * The LZ survey book, for admins (docs/LZ_SURVEYS.md, step L1), opened from the account menu: the book's list, and a survey open in
 * its editor, in one window nearly the size of the screen. Leaving a survey with changes not saved asks first, as closing an LZ/PZ
 * does: Cancel, Don't save, or Save.
 */

const lzCount = (n) => `${n} ${n === 1 ? "LZ" : "LZs"}`;

const SurveyBook = ({ onClose, client }) => {
  const surveys = useSurveys(client ? { client } : undefined);
  const { show } = useToast();
  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [leaving, setLeaving] = useState(null); // what to do once the question is answered: "book" | "close"
  const [archiving, setArchiving] = useState(false);
  const { survey, dirty, busy } = surveys;

  useEffect(() => {
    surveys.refresh();
    // Once, when the book opens.
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
    else surveys.closeSurvey();
  };
  const askToLeave = (where) => (survey && dirty ? setLeaving(where) : leave(where));

  const save = async () => {
    const saved = await surveys.save();
    if (saved) show({ message: `Saved ${saved.name}, revision ${saved.revision}.` });
    return saved;
  };
  saveRef.current = survey && dirty && !busy && !survey.archived ? save : null;

  const title = survey ? `${survey.hlz_id ? `${survey.hlz_id} · ` : ""}${survey.name}` : surveys.list.book?.name || "HLZ survey book";
  const note = survey
    ? dirty
      ? "Changes not saved."
      : `Saved · revision ${survey.revision}`
    : `${lzCount(surveys.list.surveys.filter((s) => !s.archived).length)} · admins only until each is published`;

  return (
    <>
      <Dialog
        title={title}
        subtitle={survey ? "LZ survey" : "LZ surveys"}
        icon="hexagon"
        className="survey-window"
        onClose={() => askToLeave("close")}
        note={note}
        footer={survey ? (
          <>
            <button type="button" className="ui-btn ui-btn--38" onClick={() => askToLeave("book")}>
              <Icon name="chevronLeft" size={14} />
              Book
            </button>
            {survey.archived ? (
              <button type="button" className="ui-btn ui-btn--38" disabled={Boolean(busy)} onClick={() => surveys.setArchived(false)}>Restore</button>
            ) : (
              <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" disabled={Boolean(busy)} onClick={() => setArchiving(true)}>Archive</button>
            )}
            <button type="button" className="ui-btn ui-btn--primary ui-btn--38" disabled={!dirty || Boolean(busy) || survey.archived} onClick={save}>
              <Icon name="save" size={14} />
              {busy === "saving" ? "Saving…" : "Save"}
            </button>
          </>
        ) : (
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={() => setRenaming(true)} disabled={!surveys.list.book}>
            <Icon name="pencil" size={14} />
            Rename the book
          </button>
        )}
      >
        {survey ? (
          <SurveyEditor surveys={surveys} />
        ) : (
          <SurveyBookPage
            surveys={surveys}
            onNew={() => setCreating(true)}
            onOpen={async (uuid) => {
              if (!(await surveys.openSurvey(uuid))) show({ message: surveys.error ?? "The survey could not be opened.", tone: "error" });
            }}
          />
        )}
      </Dialog>
      {creating && (
        <NewSurveyDialog
          onCancel={() => setCreating(false)}
          onCreate={async (values) => {
            await surveys.create(values);
            setCreating(false);
          }}
        />
      )}
      {renaming && (
        <NameDialog
          title="Rename the book"
          label="Name"
          initialName={surveys.list.book?.name ?? ""}
          upperCase
          confirmLabel="Rename"
          onCancel={() => setRenaming(false)}
          onConfirm={async (name) => {
            await surveys.renameBook(name);
            setRenaming(false);
          }}
        />
      )}
      {archiving && (
        <ConfirmDialog
          title={`Archive ${survey?.name ?? "this survey"}?`}
          text="It leaves the book but is kept, with everything in it, and can be restored. A survey is never deleted: a printed book may hold it."
          confirmLabel="Archive"
          onCancel={() => setArchiving(false)}
          onConfirm={async () => {
            setArchiving(false);
            if (await surveys.setArchived(true)) show({ message: `${survey.name} archived.` });
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
