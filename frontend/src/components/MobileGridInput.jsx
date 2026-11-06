import React from 'react';
import { formatMGRS } from '../utils/Helpers';
import TargetField from './TargetField';
import './MobileGridInput.css';

// The phone's target field, over the map. A grid is spaced as it is typed; the
// places it could be drop down under it (TargetField). Text that starts with a
// letter is a place's name (no grid does), and is left as typed.
const MobileGridInput = ({ gridInput, setGridInput, onChooseTarget, onShare, targetNear, searchPlaces = null }) => (
  <div className="mobile-top-search-container">
    <TargetField
      value={gridInput}
      onChange={(text) => setGridInput(searchPlaces && /^\s*\p{L}/u.test(text) ? text : formatMGRS(text))}
      onChoose={onChooseTarget}
      onShare={onShare}
      near={targetNear}
      searchPlaces={searchPlaces}
      rowClassName="mobile-search-row"
      inputClassName="top-search-input"
      buttonClassName="top-search-btn"
      inputProps={{ type: 'text', onFocus: (e) => e.target.select() }}
    />
  </div>
);

export default MobileGridInput;
