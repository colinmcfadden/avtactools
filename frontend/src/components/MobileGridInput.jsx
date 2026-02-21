import React from 'react';
import './MobileGridInput.css';

const MobileGridInput = ({ gridInput, setGridInput, handleSearch }) => {
  return (
    <div className="mobile-top-search-container">
      <input
        type="text"
        className="top-search-input"
        placeholder="Enter MGRS Grid..."
        value={gridInput}
        onChange={(e) => setGridInput(e.target.value)}
        autoComplete="off"
      />
      <button onClick={handleSearch} className="top-search-btn">
        GO
      </button>
    </div>
  );
};

export default MobileGridInput;