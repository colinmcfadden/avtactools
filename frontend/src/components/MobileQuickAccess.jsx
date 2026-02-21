import React from 'react';
import './MobileQuickAccess.css'; // We will create this CSS file next

const MobileQuickAccess = ({ 
    addHelo, 
    addPZMarker, 
    addSector 
}) => {

  // Reusable SVG for PZ Button (copied from Controls.jsx)
  const pzButtonSvg = (
    <svg width="24" height="24" viewBox="0 0 100 80" style={{ overflow: "visible" }}>
      <path d="M 40,25 L 10,25 L 10,15 L -10,40 L 10,65 L 10,55 L 40,55 Z" fill="#00b5e2" stroke="none" />
      <circle cx="70" cy="40" r="15" fill="none" stroke="#ef4444" strokeWidth="8" />
    </svg>
  );

  return (
    <div className="mobile-quick-access-container">
        
      {/* Helo Button */}
      <button onClick={addHelo} className="qa-btn" title="Add Helo">
        <img src="/icons/helicopter.png" alt="Helo" className="qa-icon-img" />
      </button>

      {/* PZ Button */}
      <button onClick={addPZMarker} className="qa-btn" title="Add PZ">
        {pzButtonSvg}
      </button>

      {/* Sector Button */}
      <button onClick={addSector} className="qa-btn" title="Add Sector">
        <svg viewBox="0 0 50 50" width="24" height="24">
          <polygon points="25,5 45,40 5,40" fill="rgba(147, 112, 219, 0.5)" stroke="#9370DB" strokeWidth="3" />
        </svg>
      </button>

    </div>
  );
};

export default MobileQuickAccess;