/*
 * One visit to a field is one step to undo, however many keystrokes it takes. The form calls
 * beginFieldVisit as a field takes the focus; what it then changes is recorded under this key, and
 * edits sharing a key merge (history.js). Read when the person acts, never in a reducer.
 */
let visit = 0;

export const beginFieldVisit = () => {
  visit += 1;
};

export const currentFieldVisit = () => `field-${visit}`;
