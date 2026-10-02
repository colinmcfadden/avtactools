import React from 'react';
import { formatMGRS } from '../utils/Helpers';
import CoordinateHint from './CoordinateHint';
import './MobileGridInput.css';

const MobileGridInput = ({ gridInput, setGridInput, handleSearch }) => {
  return (
    <div className="mobile-top-search-container">
      {/* Input and button stay on one row; the hint stacks underneath. */}
      <div className="mobile-search-row">
        <input
          type="text"
          className="top-search-input"
          placeholder="MGRS grid or lat/long"
          value={gridInput}
          onFocus={(e) => e.target.select()}
          onChange={(e) => setGridInput(formatMGRS(e.target.value))}
          autoComplete="off"
        />
        <button onClick={handleSearch} className="top-search-btn">
          GO
        </button>
      </div>
      <CoordinateHint value={gridInput} />
    </div>
  );
};

export default MobileGridInput;