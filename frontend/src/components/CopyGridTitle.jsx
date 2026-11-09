import React from "react";
import Icon from "../feature/ui/Icon";
import { useToast } from "../feature/ui/Toast";
import { copyText } from "../utils/clipboard";

/**
 * The grid at the top of the map's right-click menu, which copies itself when clicked (as a map app copies an
 * address): a real button, so a keyboard and a screen reader reach it, then a toast says it was copied and the
 * menu closes.
 */
const CopyGridTitle = ({ grid, onCopied }) => {
  const { show: toast } = useToast();
  const copy = async () => {
    if (await copyText(grid)) toast({ message: "Copied", duration: 2000 });
    else toast({ tone: "error", message: "The grid could not be copied." });
    onCopied?.();
  };
  return (
    <button type="button" className="ctx-title ctx-title--copy" aria-label={`Copy grid ${grid}`} title="Copy grid" onClick={copy}>
      <span>{grid}</span>
      <Icon name="copy" size={12} />
    </button>
  );
};

export default CopyGridTitle;
