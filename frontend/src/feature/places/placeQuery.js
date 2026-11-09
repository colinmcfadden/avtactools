/**
 * Whether what is typed in the target field could be a place's name or identifier (docs/PLACE_SEARCH.md §6), worked
 * out from the first couple of characters, so the field asks the place search only for text that can be one:
 *
 *   "place"   a name or a letter-led identifier (DZJ, KATL, Blairsville, Neel Gap): only places are offered, and the
 *             grid and lat/long reader's sentence ("Type a grid, such as …") is not shown, since the text is neither.
 *   "either"  a digit-led identifier (1GA3, 0J0, 4A9), which a grid starts as too ("16S"): places are offered under
 *             what the reader says.
 *   null      a grid or a coordinate the reader has read, a hemisphere letter starting a coordinate ("N34"), digits
 *             and punctuation, or too little text: nothing is asked.
 */

// Characters of a name or identifier before anything is asked, as the server's MIN_CHARS (places/search.py).
export const PLACE_MIN_CHARS = 2;
// A digit-led identifier is at most five characters (the FAA's are three or four); a longer digit-led text is a grid.
const DIGIT_IDENT = /^[0-9][A-Za-z0-9]{1,4}$/;

// The text with its accents taken off, as the index folds it (places/text.py).
const plain = (text) => text.normalize("NFKD").replace(/[̀-ͯ]/g, "");

export const placeQueryOf = (text, reading) => {
  if (reading?.choices?.length) return null;
  const typed = plain(String(text ?? "").trim()).replace(/^[^A-Za-z0-9]+/, "");
  const squeezed = typed.replace(/[^A-Za-z0-9]/g, "");
  if (squeezed.length < PLACE_MIN_CHARS) return null;
  if (/^[A-Za-z]/.test(typed)) {
    // "N34 …", "W 084°": a coordinate with its hemisphere first.
    if (/^[NSEW][\s°º]*[0-9]/i.test(typed)) return null;
    return "place";
  }
  if (DIGIT_IDENT.test(squeezed) && /[A-Za-z]/.test(squeezed) && !/\s/.test(typed)) return "either";
  return null;
};
