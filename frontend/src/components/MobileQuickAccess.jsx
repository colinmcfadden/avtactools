import React, { useState, useRef, useEffect } from 'react';
import { UNIT_TYPES } from '../feature/unit/UnitIcons';
import { symbolDataUri } from '../feature/symbols/milsym';
import './MobileQuickAccess.css';

const MobileQuickAccess = ({
    addHelo,
    addPZMarker,
    addSector,
    addUnit,
    addGoAround,
    enableExportMode,
    onDownloadClick,
    exportBox,
    isExporting,
    exportProgress,
    isSketching,
    toggleRouteSketch,
    onOpenUnitBuilder,
    // Optional diagram lifecycle contract. Defaults retain the previous
    // always-available mobile quick-access behavior for legacy callers.
    canAnalyze: canAnalyzeProp,
    canUseDiagramTools: canUseDiagramToolsProp,
    canSaveDiagram: canSaveDiagramProp,
    diagramStatus,
    diagramReadinessText,
    readinessText,
    features = {},
}) => {
  const can = (key) => features[key] !== false;
  const [isUnitMenuOpen, setIsUnitMenuOpen] = useState(false);
  const [isGAMenuOpen, setIsGAMenuOpen] = useState(false);

  const hasLifecycleProps =
    canAnalyzeProp !== undefined ||
    canUseDiagramToolsProp !== undefined ||
    canSaveDiagramProp !== undefined ||
    Boolean(diagramStatus) ||
    Boolean(diagramReadinessText) ||
    Boolean(readinessText);
  const canUseDiagramTools = canUseDiagramToolsProp ?? true;
  const canSaveDiagram = canSaveDiagramProp ?? canUseDiagramTools;
  const canExport = canUseDiagramTools && canSaveDiagram;
  const readinessMessage =
    diagramReadinessText ??
    readinessText ??
    (hasLifecycleProps && !canUseDiagramTools
      ? canAnalyzeProp === false
        ? "Set a target and analyze the LZ to unlock planning tools."
        : "Analyze the LZ to unlock planning tools."
      : null);

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

  useEffect(() => {
    if (!canUseDiagramTools) {
      setIsUnitMenuOpen(false);
      setIsGAMenuOpen(false);
    }
  }, [canUseDiagramTools]);

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

  const cropIcon = (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ width: '16px', height: '16px' }}>
        <path d="M6.13 1L6 16a2 2 0 0 0 2 2h15"></path><path d="M1 6.13L16 6a2 2 0 0 1 2 2v15"></path>
    </svg>
  );

  const downloadIcon = (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ width: '16px', height: '16px' }}>
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line>
    </svg>
  );

  const handleUnitClick = (path) => {
    if (!canUseDiagramTools) return;
    addUnit(path); 
    setIsUnitMenuOpen(false); 
  };

  const handleGAClick = (direction) => {
    if (!canUseDiagramTools) return;
    addGoAround(direction); // Passes "left" or "right" to your spawn function
    setIsGAMenuOpen(false);
  };

  const handleExportMode = () => {
    if (!canExport) return;
    enableExportMode();
  };

  const handleDownload = () => {
    if (!canExport || !exportBox || isExporting) return;
    onDownloadClick();
  };

  return (
    <div className="mobile-quick-access-container">
      {/* The readiness hint ("Set a target…") is intentionally NOT rendered here.
          On mobile it pushed this vertical icon rail toward mid-screen; the same
          message still shows inside the control panel once it's opened. The text
          is retained as button titles below so disabled buttons stay explained. */}
      {/* Route Sketch Button (toggles draw mode) */}
      {can("routes") && (
      <button
        onClick={toggleRouteSketch}
        className={`qa-btn ${isSketching ? 'active-qa-btn' : ''}`}
        title={isSketching ? 'End Route' : 'Sketch Route'}
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke={isSketching ? '#ffffff' : '#64D2FF'}
          strokeWidth="2"
          width="24"
          height="24"
        >
          <circle cx="4" cy="20" r="2" />
          <circle cx="12" cy="9" r="2" />
          <circle cx="20" cy="15" r="2" />
          <path d="M5.5 18.5 L10.5 10.5 M13.7 10 L18.3 14" />
        </svg>
      </button>
      )}

      {can("lz_pz_tools") && (<>
      {/* Helo Button */}
      <button
        onClick={() => canUseDiagramTools && addHelo()}
        disabled={!canUseDiagramTools}
        className="qa-btn"
        title={canUseDiagramTools ? "Add Helo" : readinessMessage}
      >
        <img src="/icons/helicopter.png" alt="Helo" className="qa-icon-img" style={{ width: '24px', height: '24px', objectFit: 'contain' }} />
      </button>

      {/* PZ Button */}
      <button
        onClick={() => canUseDiagramTools && addPZMarker()}
        disabled={!canUseDiagramTools}
        className="qa-btn"
        title={canUseDiagramTools ? "Add PZ" : readinessMessage}
      >
        {pzButtonSvg}
      </button>

      {/* Sector Button */}
      <button
        onClick={() => canUseDiagramTools && addSector()}
        disabled={!canUseDiagramTools}
        className="qa-btn"
        title={canUseDiagramTools ? "Add Sector" : readinessMessage}
      >
        <svg viewBox="0 0 50 50" width="24" height="24">
          <polygon points="25,5 45,40 5,40" fill="rgba(147, 112, 219, 0.5)" stroke="#9370DB" strokeWidth="3" />
        </svg>
      </button>
    
      {/* Unit Button with Pop-out Menu */}
      <div className="unit-menu-wrapper" ref={menuRef}>
        <button 
            onClick={() => {
              if (!canUseDiagramTools) return;
              setIsUnitMenuOpen(!isUnitMenuOpen);
            }}
            disabled={!canUseDiagramTools}
            className={`qa-btn ${isUnitMenuOpen ? 'active-qa-btn' : ''}`} 
            title={canUseDiagramTools ? "Add Unit" : readinessMessage}
        >
          <img src="/icons/tactical/infantry.svg" alt="Units" className="qa-icon-img" style={{ width: '24px', height: '24px' }} />
        </button>

        {/* The Flyout Tray */}
        {isUnitMenuOpen && (
          <div className="unit-flyout-menu">
            <button
              className="qa-btn flyout-btn"
              title="Build MIL-STD symbol"
              onClick={() => {
                if (!canUseDiagramTools) return;
                onOpenUnitBuilder?.();
                setIsUnitMenuOpen(false);
              }}
            >
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="#00b5e2" strokeWidth="2">
                <rect x="4" y="6" width="16" height="12" rx="2" />
                <path d="M12 9v6M9 12h6" />
              </svg>
            </button>
            {UNIT_TYPES.map((unit) => (
              <button
                key={unit.id}
                className="qa-btn flyout-btn"
                title={`Add ${unit.label}`}
                onClick={() => {
                        handleUnitClick(unit);
                      }}
              >
                <img src={symbolDataUri(unit.sidc, { size: 24 })} alt={unit.label} className="qa-icon-img" style={{ width: '24px', height: '24px' }} />
              </button>
            ))}
          </div>
        )}
      </div>

      {/* --- GO AROUND MENU --- */}
      <div className="unit-menu-wrapper" ref={gaMenuRef}>
        <button 
            onClick={() => {
                if (!canUseDiagramTools) return;
                setIsGAMenuOpen(!isGAMenuOpen);
                setIsUnitMenuOpen(false); // Close the other menu if open
            }} 
            disabled={!canUseDiagramTools}
            className={`qa-btn ${isGAMenuOpen ? 'active-qa-btn' : ''}`} 
            title={canUseDiagramTools ? "Add Go Around" : readinessMessage}
        >
          {gaRightIcon}
        </button>

        {isGAMenuOpen && (
          <div className="unit-flyout-menu">
            <button 
                className="qa-btn flyout-btn" 
                title="Go Around Left"
                disabled={!canUseDiagramTools}
                onClick={() => handleGAClick("left")}
            >
              {gaLeftIcon}
            </button>
            <button 
                className="qa-btn flyout-btn" 
                title="Go Around Right"
                disabled={!canUseDiagramTools}
                onClick={() => handleGAClick("right")}
            >
              {gaRightIcon}
            </button>
          </div>
        )}
      </div>
      </>)}

      {/* --- NEW: Bottom Right Export Controls --- */}
      {can("exports") && (
      <div className="mobile-export-container">
        
        {/* Progress Bar (Only shows when exporting) */}
        {isExporting && (
            <div className="mobile-progress-bg">
                <div className="mobile-progress-fill" style={{ width: `${exportProgress}%` }}></div>
            </div>
        )}

        <div className="mobile-export-buttons">
            <button 
                onClick={handleExportMode}
                disabled={!canExport}
                title={canExport ? "Set the capture area" : readinessMessage}
                className={`mobile-pill-btn blue-pill ${!canExport ? 'disabled' : ''}`}
            >
                {cropIcon} <span>Capture</span>
            </button>

            <button 
                onClick={handleDownload}
                disabled={!canExport || !exportBox || isExporting}
                title={!canExport ? readinessMessage : !exportBox ? "Set a capture area before exporting." : "Export LZ card"}
                className={`mobile-pill-btn green-pill ${!canExport || !exportBox || isExporting ? 'disabled' : ''}`}
            >
                {downloadIcon} <span>{isExporting ? 'Processing...' : 'Export'}</span>
            </button>
        </div>
      </div>
      )}
    </div>
  );
};

export default MobileQuickAccess;
