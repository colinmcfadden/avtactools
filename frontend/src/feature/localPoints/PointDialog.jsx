import React, { useId, useMemo, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { nearestFirst, readTarget } from "../../utils/targetReading";
import { toMgrs } from "../../utils/mgrs";
import { DEFAULT_GROUP, LIMITS, NEW_SET_NAME, pointFromForm, pointGroups } from "./customPoints";

/*
 * Making one local point, or changing one. The place is typed as anything the target field reads (an
 * MGRS grid or a lat/long), or comes filled in from where the map was right-clicked. A place left
 * as it opened keeps the exact position it had: a grid shown to five digits is truncated, and reading
 * it back would move the point up to a metre for no reason.
 */

const NEW_SET = "__new";

const FieldProblem = ({ text }) => (text ? <div className="imports__note imports__note--warn">{text}</div> : null);

// Words under a field stand outside its label: a screen reader would read them as the field's name.
const Field = ({ label, children, input }) => {
  const id = useId();
  return (
    <div className="points-form__field">
      <label className="ui-label" htmlFor={id}>{label}</label>
      {input(id)}
      {children}
    </div>
  );
};

const PointDialog = ({ ask, sets = [], near, onCancel, onSave }) => {
  const editing = ask.point ?? null;
  const start = editing ?? ask.at ?? null;
  const startText = start ? toMgrs(start.lat, start.lon) ?? `${start.lat}, ${start.lon}` : "";

  const [name, setName] = useState(editing?.name ?? "");
  const [place, setPlace] = useState(startText);
  const [elevation, setElevation] = useState(typeof editing?.elevationFt === "number" ? String(editing.elevationFt) : "");
  const [group, setGroup] = useState(editing?.group ?? ask.group ?? DEFAULT_GROUP);
  const [description, setDescription] = useState(editing?.description ?? "");
  const [target, setTarget] = useState(ask.setId ?? sets[sets.length - 1]?.id ?? NEW_SET);
  const [newSetName, setNewSetName] = useState(NEW_SET_NAME);
  const [picked, setPicked] = useState(0);
  const [problems, setProblems] = useState({});

  const untouched = place === startText && start;
  const reading = useMemo(() => (untouched ? null : readTarget(place)), [untouched, place]);
  const choices = useMemo(() => (reading ? nearestFirst(reading.choices, near) : []), [reading, near]);
  const choice = choices[Math.min(picked, choices.length - 1)] ?? null;
  const position = untouched ? { lat: start.lat, lon: start.lon } : choice;

  const groups = useMemo(() => pointGroups(sets.find((set) => set.id === target)?.points).map((one) => one.name), [sets, target]);

  // A complaint is about what was typed then: once that has changed it no longer stands.
  const typed = (field, set) => (value) => {
    set(value);
    setProblems((found) => (found[field] ? { ...found, [field]: undefined } : found));
  };

  const submit = () => {
    const made = pointFromForm({ name, position, elevation, group, description });
    const found = { ...(made.problems ?? {}) };
    if (!editing && target === NEW_SET && !newSetName.trim()) found.set = "Give the new set a name.";
    if (Object.keys(found).length > 0) {
      setProblems(found);
      return;
    }
    onSave({ setId: target === NEW_SET ? null : target, newSetName: newSetName.trim().toUpperCase(), point: made.point, pointId: editing?.id ?? null });
  };

  return (
    <Dialog
      title={editing ? `Edit ${editing.name}` : "New local point"}
      icon="mapPin"
      onClose={onCancel}
      width={440}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit}>
            <Icon name="check" size={15} />
            {editing ? "Save changes" : "Add point"}
          </button>
        </>
      }
    >
      <form
        className="points-form"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <Field label="Name" input={(id) => <input id={id} className="ui-input" value={name} maxLength={LIMITS.name} autoFocus onChange={(event) => typed("name", setName)(event.target.value.toUpperCase())} />}>
          <FieldProblem text={problems.name} />
        </Field>

        <Field
          label="Position"
          input={(id) => (
            <input
              id={id}
              className="ui-input"
              value={place}
              placeholder="MGRS grid or lat/long"
              onChange={(event) => {
                typed("position", setPlace)(event.target.value);
                setPicked(0);
              }}
            />
          )}
        >
          {choices.length === 1 && <div className="imports__note">{choices[0].latLong}</div>}
          {choices.length > 1 && (
            <div className="points-form__choices" role="radiogroup" aria-label="Which place this is">
              {choices.map((one, index) => (
                <button key={one.mgrs} type="button" role="radio" aria-checked={index === picked} className={`ui-btn ui-btn--ghost ui-btn--28${index === picked ? " points-form__choice--on" : ""}`} onClick={() => setPicked(index)}>
                  {one.latLong}
                </button>
              ))}
            </div>
          )}
          {reading?.message && <div className="imports__note imports__note--warn">{reading.message}</div>}
          {!place.trim() && <div className="imports__note">Or right-click the map and choose Add Local Point Here.</div>}
          <FieldProblem text={problems.position} />
        </Field>

        <div className="points-form__row">
          <Field label="Elevation (ft)" input={(id) => <input id={id} className="ui-input" value={elevation} inputMode="decimal" placeholder="Optional" onChange={(event) => typed("elevation", setElevation)(event.target.value)} />}>
            <FieldProblem text={problems.elevation} />
          </Field>
          <Field label="Group" input={(id) => <input id={id} className="ui-input" value={group} maxLength={LIMITS.group} list="point-groups" onChange={(event) => setGroup(event.target.value)} />}>
            <datalist id="point-groups">
              {groups.map((one) => (
                <option key={one} value={one} />
              ))}
            </datalist>
          </Field>
        </div>

        <Field label="Description" input={(id) => <input id={id} className="ui-input" value={description} maxLength={LIMITS.description} placeholder="Optional" onChange={(event) => setDescription(event.target.value)} />} />

        {!editing && (
          <div className="points-form__row">
            <Field
              label="Point set"
              input={(id) => (
                <select id={id} className="ui-input" value={target} onChange={(event) => setTarget(event.target.value)}>
                  {sets.map((set) => (
                    <option key={set.id} value={set.id}>{set.name}</option>
                  ))}
                  <option value={NEW_SET}>New set…</option>
                </select>
              )}
            />
            {target === NEW_SET && (
              <Field label="New set's name" input={(id) => <input id={id} className="ui-input" value={newSetName} maxLength={100} onChange={(event) => typed("set", setNewSetName)(event.target.value.toUpperCase())} />}>
                <FieldProblem text={problems.set} />
              </Field>
            )}
          </div>
        )}
        {/* So Enter in a field submits, with the buttons where the Dialog puts them. */}
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </form>
    </Dialog>
  );
};

export default PointDialog;
