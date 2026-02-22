import React, { useState, useRef, useEffect } from 'react';
import { UNIT_TYPES } from './UnitIcons';
import './MobileQuickAccess.css'; 

const MobileQuickAccess = ({ 
    addHelo, 
    addPZMarker, 
    addSector,
    addUnit,
    addGoAround
}) => {
  const [isUnitMenuOpen, setIsUnitMenuOpen] = useState(false);
  const [isGAMenuOpen, setIsGAMenuOpen] = useState(false);

  const menuRef = useRef(null);
  const gaMenuRef = useRef(null);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setIsUnitMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("touchstart", handleClickOutside);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("touchstart", handleClickOutside);
    };
  }, []);

  // Reusable SVG for PZ Button
  const pzButtonSvg = (
    <svg width="24" height="24" viewBox="0 0 100 80" style={{ overflow: "visible" }}>
      <path d="M 40,25 L 10,25 L 10,15 L -10,40 L 10,65 L 10,55 L 40,55 Z" fill="#00b5e2" stroke="none" />
      <circle cx="70" cy="40" r="15" fill="none" stroke="#ef4444" strokeWidth="8" />
    </svg>
  );

  const gaRightIcon = (
    <svg viewBox="0 0 100 100" width="24" height="24" style={{ overflow: "visible" }}>
      <path d="M10,50 Q40,50 60,80 L50,85 L80,95 L95,65 L85,70 Q70,20 10,20 Z" fill="#FFC107" stroke="black" strokeWidth="4" />
    </svg>
  );

  const gaLeftIcon = (
    <svg viewBox="0 0 100 100" width="24" height="24" style={{ overflow: "visible" }}>
      <path d="M90,50 Q60,50 40,80 L50,85 L20,95 L5,65 L15,70 Q30,20 90,20 Z" fill="#FFC107" stroke="black" strokeWidth="4" />
    </svg>
  );

  const handleUnitClick = (path) => {
    addUnit(path); 
    setIsUnitMenuOpen(false); 
  };

  const handleGAClick = (direction) => {
    addGoAround(direction); // Passes "left" or "right" to your spawn function
    setIsGAMenuOpen(false);
  };

  return (
    <div className="mobile-quick-access-container">
      {/* Helo Button */}
      <button onClick={addHelo} className="qa-btn" title="Add Helo">
        <img src="/icons/helicopter.png" alt="Helo" className="qa-icon-img" style={{ width: '24px', height: '24px', objectFit: 'contain' }} />
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
    
      {/* Unit Button with Pop-out Menu */}
      <div className="unit-menu-wrapper" ref={menuRef}>
        <button 
            onClick={() => setIsUnitMenuOpen(!isUnitMenuOpen)} 
            className={`qa-btn ${isUnitMenuOpen ? 'active-qa-btn' : ''}`} 
            title="Add Unit"
        >
          <img src="/icons/tactical/infantry.svg" alt="Units" className="qa-icon-img" style={{ width: '24px', height: '24px' }} />
        </button>

        {/* The Flyout Tray */}
        {isUnitMenuOpen && (
          <div className="unit-flyout-menu">
            {UNIT_TYPES.map((unit) => (
              <button 
                key={unit.id} 
                className="qa-btn flyout-btn" 
                title={`Add ${unit.name}`}
                onClick={() => {
                        handleUnitClick(unit);
                      }}
              >
                <img src={unit.path} alt={unit.name} className="qa-icon-img" style={{ width: '24px', height: '24px' }} />
              </button>
            ))}
          </div>
        )}
      </div>

      {/* --- GO AROUND MENU --- */}
      <div className="unit-menu-wrapper" ref={gaMenuRef}>
        <button 
            onClick={() => {
                setIsGAMenuOpen(!isGAMenuOpen);
                setIsUnitMenuOpen(false); // Close the other menu if open
            }} 
            className={`qa-btn ${isGAMenuOpen ? 'active-qa-btn' : ''}`} 
            title="Add Go Around"
        >
          {gaRightIcon}
        </button>

        {isGAMenuOpen && (
          <div className="unit-flyout-menu">
            <button 
                className="qa-btn flyout-btn" 
                title="Go Around Left"
                onClick={() => handleGAClick("left")}
            >
              {gaLeftIcon}
            </button>
            <button 
                className="qa-btn flyout-btn" 
                title="Go Around Right"
                onClick={() => handleGAClick("right")}
            >
              {gaRightIcon}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

export default MobileQuickAccess;