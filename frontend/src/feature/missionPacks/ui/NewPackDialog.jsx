import React, { useId, useRef, useState } from "react";
import Avatar from "../../ui/Avatar";
import Chip from "../../ui/Chip";
import Dialog from "../../ui/Dialog";
import Icon from "../../ui/Icon";
import * as packApi from "../packApi";
import PersonPicker from "./PersonPicker";
import "../../imports/imports.css";
import "./packs.css";

/*
 * Making a Mission Pack (screen NewPack): a name, a line on what it is for, and who can open it:
 * just the maker, a team (as editors or viewers), or particular people, who can also be added on top
 * of a team. People on the maker's teams join at once; anyone else gets an email. After it is made
 * the pack opens, and if asked, the Library opens to choose what to put in it.
 */

const WHO = [
  { key: "me", label: "Just me" },
  { key: "team", label: "A team" },
  { key: "people", label: "Specific people" },
];

const NewPackDialog = ({ teams = [], api = packApi, onCreated, onCancel }) => {
  const id = useId();
  const nameRef = useRef(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [who, setWho] = useState(teams.length ? "team" : "me");
  const [teamId, setTeamId] = useState(teams[0]?.id ?? null);
  const [teamRole, setTeamRole] = useState("editor");
  const [people, setPeople] = useState([]);
  const [startFromLibrary, setStartFromLibrary] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const addPerson = (pick) =>
    setPeople((list) =>
      list.some((p) => (pick.user ? p.user?.id === pick.user.id : p.email === pick.email)) ? list : [...list, { ...pick, role: "editor" }],
    );

  const create = async () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Give the pack a name.");
      nameRef.current?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const pack = await api.createPack({
        name: trimmed,
        description: description.trim(),
        ...(who === "team" && teamId != null ? { team_id: teamId, team_role: teamRole } : {}),
      });
      // People are added once the pack exists; one who cannot be added is reported, not fatal.
      const failed = [];
      if (who !== "me") {
        for (const person of people) {
          try {
            if (person.user) await api.addMember(pack.uuid, person.user.id, person.role);
            else await api.inviteToPack(pack.uuid, person.email, person.role);
          } catch (err) {
            failed.push(person.user ? person.user.name || person.user.email : person.email);
          }
        }
      }
      onCreated(pack, { startFromLibrary, failed });
    } catch (err) {
      setError(api.packErrorMessage?.(err) || "The pack could not be made. Check the connection and try again.");
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="New Mission Pack"
      subtitle="A shared space for one operation. Everyone in it edits the same LZ/PZs, routes and point sets live."
      icon="layers"
      iconTone="pack"
      width="xwide"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={nameRef}
      note={
        <span style={{ display: "inline-flex", gap: 6, alignItems: "flex-start" }}>
          <Icon name="info" size={13} style={{ marginTop: 2, flex: "none" }} />
          While a pack is open, everything you create goes into it and its members can see it. Threats are never added.
        </span>
      }
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--pack ui-btn--38" onClick={create} disabled={busy}>
            <Icon name="plus" size={15} />
            {busy ? "Creating" : "Create pack"}
          </button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          create();
        }}
      >
        <div className="ui-field">
          <label className="ui-field__label" htmlFor={`${id}-name`}>Name</label>
          <input
            ref={nameRef}
            id={`${id}-name`}
            className={`ui-input${error ? " ui-input--error" : ""}`}
            value={name}
            maxLength={100}
            autoComplete="off"
            placeholder="OP DK"
            style={{ textTransform: "uppercase" }}
            onChange={(event) => {
              setName(event.target.value.toUpperCase());
              setError(null);
            }}
          />
          {error && <div className="ui-field__hint ui-field__hint--error">{error}</div>}
        </div>
        <div className="ui-field" style={{ marginTop: 12 }}>
          <label className="ui-field__label" htmlFor={`${id}-desc`}>Description (optional)</label>
          <textarea
            id={`${id}-desc`}
            className="ui-input"
            rows={2}
            maxLength={2000}
            placeholder="What it is for, so people know what they are opening."
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </div>

        <div className="ui-label packs-section-label">Who can open it</div>
        <div className="imports__seg" role="radiogroup" aria-label="Who can open it" style={{ width: "100%", height: 34 }}>
          {WHO.map((option) => (
            <button
              key={option.key}
              type="button"
              role="radio"
              aria-checked={who === option.key}
              disabled={option.key === "team" && teams.length === 0}
              title={option.key === "team" && teams.length === 0 ? "You are not in a team yet. Make one under Manage teams." : undefined}
              className={`imports__seg-item${who === option.key ? " imports__seg-item--on" : ""}`}
              style={{ height: 26 }}
              onClick={() => setWho(option.key)}
            >
              {option.label}
            </button>
          ))}
        </div>

        {who === "team" && (
          <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
            <div className="ui-field" style={{ flex: 1, minWidth: 0 }}>
              <label className="ui-field__label" htmlFor={`${id}-team`}>Team</label>
              <select id={`${id}-team`} className="ui-input ui-input--36" value={teamId ?? ""} onChange={(event) => setTeamId(Number(event.target.value))}>
                {teams.map((team) => (
                  <option key={team.id} value={team.id}>
                    {team.name} · {team.member_count} {team.member_count === 1 ? "member" : "members"}
                  </option>
                ))}
              </select>
            </div>
            <div className="ui-field" style={{ width: 130 }}>
              <label className="ui-field__label" htmlFor={`${id}-team-role`}>They can</label>
              <select id={`${id}-team-role`} className="ui-input ui-input--36" value={teamRole} onChange={(event) => setTeamRole(event.target.value)}>
                <option value="editor">Edit</option>
                <option value="viewer">View</option>
              </select>
            </div>
          </div>
        )}

        {who !== "me" && (
          <div style={{ marginTop: 12 }}>
            <PersonPicker
              label={who === "team" ? "Also invite by name or email" : "Invite by name or email"}
              exclude={people.filter((p) => p.user).map((p) => p.user.id)}
              search={api.searchPeople}
              onPick={addPerson}
            />
            {people.length > 0 && (
              <div className="packs-chips-input">
                {people.map((person, index) => (
                  <Chip key={person.user ? `u${person.user.id}` : person.email} tone="pack" dot={false}>
                    {person.user ? <Avatar person={person.user} size="xs" title="" /> : <Icon name="mail" size={11} />}
                    {person.user ? person.user.name || person.user.email : person.email}
                    {" · "}
                    <button
                      type="button"
                      className="packs-chip-remove"
                      aria-label={`${person.role === "editor" ? "Editor" : "Viewer"}: make ${person.user?.name || person.email} ${person.role === "editor" ? "a viewer" : "an editor"}`}
                      onClick={() => setPeople((list) => list.map((p, i) => (i === index ? { ...p, role: p.role === "editor" ? "viewer" : "editor" } : p)))}
                    >
                      {person.role === "editor" ? "Editor" : "Viewer"}
                    </button>
                    <button
                      type="button"
                      className="packs-chip-remove"
                      aria-label={`Do not invite ${person.user?.name || person.email}`}
                      onClick={() => setPeople((list) => list.filter((_, i) => i !== index))}
                    >
                      <Icon name="x" size={11} />
                    </button>
                  </Chip>
                ))}
              </div>
            )}
          </div>
        )}

        <label className="shell-set__check" style={{ marginTop: 16 }}>
          <input type="checkbox" checked={startFromLibrary} onChange={(event) => setStartFromLibrary(event.target.checked)} />
          Start with items from my Library · choose after creating
        </label>
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

export default NewPackDialog;
