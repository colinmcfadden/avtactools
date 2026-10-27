import React, { useCallback, useState } from "react";
import { useToast } from "../ui/Toast";
import PagePreview, { saveBlob } from "./PagePreview";
import { Revisions } from "./Publishing";

/*
 * A published survey as a reader opens it (docs/LZ_SURVEYS.md §7): its page as signed off, the latest revision unless another is
 * chosen, and its PDF. Never the draft an editor may have under way.
 */

const fileName = (reading, rev) => `${[reading.hlz_id, reading.name, `REV ${rev}`].filter(Boolean).join(" ")}.pdf`;

const SurveyReader = ({ surveys }) => {
  const { reading } = surveys;
  const { show } = useToast();
  const [rev, setRev] = useState(reading?.published_revision ?? null);
  const { loadPage, loadPdf } = surveys;
  const uuid = reading?.uuid;
  const load = useCallback(() => loadPage(uuid, { rev }), [loadPage, uuid, rev]);
  if (!reading) return null;
  const signoff = reading.signoff;
  return (
    <div className="survey-reader">
      <div className="survey-editor__ident">
        <strong>{[reading.hlz_id, reading.name].filter(Boolean).join(" · ")}</strong>
        <span className="ui-chip">REV {reading.published_revision}</span>
        {signoff && (
          <span className="survey-muted">
            Signed off by {[signoff.rank, signoff.name].filter(Boolean).join(" ")}{signoff.title ? `, ${signoff.title}` : ""} · {signoff.date}
          </span>
        )}
      </div>
      <PagePreview
        key={rev}
        heading={rev === reading.published_revision ? "The page" : `REV ${rev}`}
        title={`${reading.name}, REV ${rev}`}
        load={load}
        loadPdf={() => loadPdf(uuid, { rev })}
        fileName={fileName(reading, rev)}
        autoLoad
      />
      <Revisions
        revisions={reading.revisions}
        showing={rev}
        onShow={setRev}
        onPdf={async (number) => {
          try {
            saveBlob(await loadPdf(uuid, { rev: number }), fileName(reading, number));
          } catch (err) {
            show({ message: err?.message || "The PDF could not be made.", tone: "error" });
          }
        }}
      />
    </div>
  );
};

export default SurveyReader;
