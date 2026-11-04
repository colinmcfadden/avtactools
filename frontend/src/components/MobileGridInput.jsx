import React from 'react';
import { formatMGRS } from '../utils/Helpers';
import TargetField from './TargetField';
import './MobileGridInput.css';

// The phone's target field, over the map. A grid is spaced as it is typed; the
// places it could be drop down under it (TargetField).
const MobileGridInput = ({ gridInput, setGridInput, onChooseTarget, targetNear }) => (
  <div className="mobile-top-search-container">
    <TargetField
      value={gridInput}
      onChange={(text) => setGridInput(formatMGRS(text))}
      onChoose={onChooseTarget}
      near={targetNear}
      rowClassName="mobile-search-row"
      inputClassName="top-search-input"
      buttonClassName="top-search-btn"
      inputProps={{ type: 'text', onFocus: (e) => e.target.select() }}
    />
  </div>
);

export default MobileGridInput;
