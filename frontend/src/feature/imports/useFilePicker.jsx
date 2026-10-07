import React, { useCallback, useRef } from "react";

/**
 * A hidden file input and the call that opens it, so a menu item or a button can ask for a file
 * without a visible input of its own. `onFiles` gets the chosen files; the input is cleared so the
 * same file can be chosen again.
 */
export const useFilePicker = ({ accept, multiple = false, onFiles }) => {
  const input = useRef(null);
  const handler = useRef(onFiles);
  handler.current = onFiles;
  const open = useCallback(() => input.current?.click(), []);
  const element = (
    <input
      ref={input}
      type="file"
      accept={accept}
      multiple={multiple}
      hidden
      tabIndex={-1}
      aria-hidden="true"
      onChange={(event) => {
        const files = [...(event.target.files ?? [])];
        event.target.value = "";
        if (files.length) handler.current(files);
      }}
    />
  );
  return { open, element };
};

export default useFilePicker;
