import { useEffect, useRef } from "react";
import { requestKeptBuild } from "./useLidarTileset";

/**
 * Builds an LZ's point cloud as soon as it is saved, rather than when someone
 * first opens it in 3D — so by then it is ready.
 *
 * Only a save made in this session counts: an LZ that arrives already saved
 * (loaded from the cloud) is not a new reason to spend a build, and loading a
 * dozen old LZs would otherwise queue a dozen of them.
 */
export const useBuildOnSave = (diagrams) => {
  // diagram id -> the savedId it had when last seen
  const seen = useRef(new Map());

  useEffect(() => {
    const list = Array.isArray(diagrams) ? diagrams : Object.values(diagrams || {});
    for (const diagram of list) {
      if (!diagram?.id) continue;
      const before = seen.current.get(diagram.id);
      const now = diagram.savedId ?? null;
      seen.current.set(diagram.id, now);
      if (before === null && now !== null && diagram.target) {
        requestKeptBuild(diagram.target);
      }
    }
  }, [diagrams]);
};

export default useBuildOnSave;
