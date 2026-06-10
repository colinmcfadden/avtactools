import React, { useState, useEffect, useRef, useMemo } from "react";
import Draggable from "react-draggable";
import "./ExportModal.css";
import { BIRD_NAMES, TREE_NAMES } from "../utils/LZDictionary";

const ExportModal = ({
  isOpen,
  onClose,
  onExport,
  mapData,
  flightData,
  proximityAlerts,
  activeNotams
}) => {
  const nodeRef = useRef(null);
  const dropdownRef = useRef(null);
  const [showWarning, setShowWarning] = useState(false);
  const [showSuggestions, setShowSuggestions] = useState(false);

  const [showNotamMenu, setShowNotamMenu] = useState(false);
  const [expandedCategories, setExpandedCategories] = useState({});
  const [selectedNotams, setSelectedNotams] = useState([]);

  const [formData, setFormData] = useState({
    lz_label: "LZ",
    lz_name: "",
    objective: "",
    mgrs_grid: "",
    lat_long: "",
    elevation: "",
    call_sign: "",
    freq: "",
    ldg_formation: "STAG RIGHT",
    to_formation: "STAG RIGHT",
    land_dir: "",
    go_around: "LEFT",
    takeoff_dir: "",
    weapons_control: "HOLD",
    weapons_status: "STOWED",
    door: "RIGHT",
    load: "LEFT",
    remarks: "",
  });

  const allAvailableNotams = useMemo(() => {
    if (!activeNotams || typeof activeNotams !== "object") return [];
    return Object.values(activeNotams).flat();
  }, [activeNotams]);

  const isAllSelected =
    allAvailableNotams.length > 0 &&
    selectedNotams.length === allAvailableNotams.length;

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setShowSuggestions(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  useEffect(() => {
    if (isOpen) {
      setFormData((prev) => ({
        ...prev,
        mgrs_grid: mapData.mgrs
          ? mapData.mgrs.replace(/^(.{3})(.{2})(.{4})(.{4})$/, "$1 $2 $3 $4")
          : "",
        lat_long: mapData.latLong || "",
        elevation: mapData.elevation + "' MSL" || "",
        land_dir: flightData.landing_hdg || "",
        takeoff_dir: flightData.takeoff_hdg || "",
        go_around: flightData.goAround || "LEFT",
      }));
      // Reset NOTAMs when modal opens
      setShowNotamMenu(false);
      setSelectedNotams([]);
      setExpandedCategories({});
    }
  }, [isOpen, mapData, flightData]);

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData((prev) => ({ ...prev, [name]: value.toUpperCase() }));
    if (name === "lz_name") setShowSuggestions(true);
  };

  const handleSuggestionClick = (name) => {
    setFormData((prev) => ({ ...prev, lz_name: name }));
    setShowSuggestions(false);
  };

  const filteredSuggestions = useMemo(() => {
    const list = formData.lz_label === "LZ" ? BIRD_NAMES : TREE_NAMES;
    const query = formData.lz_name.toUpperCase();

    if (!query) {
      return [...list].sort(() => 0.5 - Math.random()).slice(0, 50);
    }

    return list
      .filter((item) => item.includes(query))
      .sort((a, b) => {
        const aStarts = a.startsWith(query);
        const bStarts = b.startsWith(query);
        if (aStarts && !bStarts) return -1;
        if (!aStarts && bStarts) return 1;
        return a.localeCompare(b);
      });
  }, [formData.lz_label, formData.lz_name]);

  const handleSubmit = () => {
    if (proximityAlerts && proximityAlerts.length > 0) {
      setShowWarning(true);
    } else {
      onExport(formData);
    }
  };

  const handleConfirmExport = () => {
    setShowWarning(false);
    onExport(formData);
  };

  const handleCancelExport = () => {
    setShowWarning(false);
  };

  const handleCloseModal = () => {
    setShowWarning(false);
    onClose();
  };

  // --- NOTAM CHECKBOX LOGIC ---
  const handleToggleNotam = (notam) => {
    if (selectedNotams.includes(notam)) {
      // Remove it from the state and the remarks text box
      setSelectedNotams((prev) => prev.filter((n) => n !== notam));
      setFormData((prev) => ({
        ...prev,
        remarks: prev.remarks
          .replace(`\n\n${notam}`, "")
          .replace(notam, "")
          .trim(),
      }));
    } else {
      // Add it to the state and append to the remarks text box
      setSelectedNotams((prev) => [...prev, notam]);
      setFormData((prev) => ({
        ...prev,
        remarks: prev.remarks ? `${prev.remarks}\n\n${notam}` : notam,
      }));
    }
  };

  const handleToggleAllNotams = () => {
    if (isAllSelected) {
      // Deselect all
      setSelectedNotams([]);
      let newRemarks = formData.remarks;
      allAvailableNotams.forEach((notam) => {
        newRemarks = newRemarks
          .replace(`\n\n${notam}`, "")
          .replace(notam, "")
          .trim();
      });
      setFormData((prev) => ({ ...prev, remarks: newRemarks }));
    } else {
      // Select all that aren't already selected
      const newlySelected = allAvailableNotams.filter(
        (n) => !selectedNotams.includes(n)
      );
      setSelectedNotams(allAvailableNotams);

      let newRemarks = formData.remarks;
      newlySelected.forEach((notam) => {
        newRemarks = newRemarks ? `${newRemarks}\n\n${notam}` : notam;
      });
      setFormData((prev) => ({ ...prev, remarks: newRemarks }));
    }
  };

  const toggleCategory = (category) => {
    setExpandedCategories((prev) => ({
      ...prev,
      [category]: !prev[category],
    }));
  };

  const renderHighlightedText = (text, highlight) => {
    if (!highlight) return text;
    const parts = text.split(new RegExp(`(${highlight})`, "gi"));
    return (
      <span>
        {parts.map((part, i) =>
          part.toLowerCase() === highlight.toLowerCase() ? (
            <span key={i} className="text-highlight">
              {part}
            </span>
          ) : (
            part
          )
        )}
      </span>
    );
  };

  if (!isOpen) return null;

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel">
        {showWarning && (
          <div className="warning-overlay">
            <div className="warning-box">
              <h4>⚠️ Proximity Alert</h4>
              <p>
                One or more helicopters are placed closer than the 60m minimum
                separation requirement.
              </p>
              <div className="warning-actions">
                <button className="cancel-btn" onClick={handleCancelExport}>
                  Go Back
                </button>
                <button
                  className="export-btn warning-proceed"
                  onClick={handleConfirmExport}
                >
                  Disregard & Export
                </button>
              </div>
            </div>
          </div>
        )}

        <div className="modal-header">
          <h3>Export LZ Card</h3>
          <button className="close-btn" onClick={onClose}>
            ×
          </button>
        </div>

        <div className="modal-body">
          {/* SECTION 1: IDENTIFIERS (Grid with different column sizes) */}
          <div className="form-grid header-grid">
            <div className="input-group">
              <label>Type</label>
              <select
                name="lz_label"
                value={formData.lz_label}
                onChange={(e) => {
                  handleChange(e);
                  setFormData((prev) => ({ ...prev, lz_name: "" })); // Clear name when type changes
                }}
              >
                <option value="LZ">LZ</option>
                <option value="PZ">PZ</option>
              </select>
            </div>

            {/* AUTOCOMPLETE DROPDOWN */}
            <div
              className="input-group span-flex"
              ref={dropdownRef}
              style={{ position: "relative" }}
            >
              <label>Name</label>
              <input
                name="lz_name"
                value={formData.lz_name}
                onChange={handleChange}
                onFocus={() => setShowSuggestions(true)}
                autoComplete="off"
                placeholder={
                  formData.lz_label === "LZ" ? "e.g. HAWK" : "e.g. OAK"
                }
              />
              {showSuggestions && filteredSuggestions.length > 0 && (
                <ul className="suggestions-dropdown">
                  {filteredSuggestions.map(name => (
                    <li key={name} onClick={() => handleSuggestionClick(name)}>
                      {renderHighlightedText(name, formData.lz_name)}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>

          <div className="form-divider">Grid Data</div>

          {/* SECTION 2: 2x2 GRID */}
          <div className="form-grid two-col-grid">
            <div className="input-group">
              <label>Objective</label>
              <input
                name="objective"
                value={formData.objective}
                onChange={handleChange}
              />
            </div>
            <div className="input-group">
              <label>MGRS Grid</label>
              <input
                name="mgrs_grid"
                value={formData.mgrs_grid}
                onChange={handleChange}
              />
            </div>
            <div className="input-group">
              <label>Lat Long</label>
              <input
                name="lat_long"
                value={formData.lat_long}
                onChange={handleChange}
              />
            </div>
            <div className="input-group">
              <label>Elevation</label>
              <input
                name="elevation"
                value={formData.elevation}
                onChange={handleChange}
              />
            </div>
          </div>

          <div className="form-divider">Tactical Data</div>

          {/* SECTION 3: TACTICAL GRID */}
          <div className="form-grid two-col-grid">
            <div className="input-group">
              <label>Call Sign</label>
              <input
                name="call_sign"
                value={formData.call_sign}
                onChange={handleChange}
              />
            </div>
            <div className="input-group">
              <label>Freq</label>
              <input
                name="freq"
                value={formData.freq}
                onChange={handleChange}
              />
            </div>

            <div className="input-group">
              <label>Land Dir</label>
              <input
                name="land_dir"
                value={formData.land_dir}
                onChange={handleChange}
              />
            </div>
            <div className="input-group">
              <label>T/O Dir</label>
              <input
                name="takeoff_dir"
                value={formData.takeoff_dir}
                onChange={handleChange}
              />
            </div>

            <div className="input-group">
              <label>Landing Formation</label>
              <select
                name="ldg_formation"
                value={formData.ldg_formation}
                onChange={handleChange}
              >
                <option value="STAG LEFT">STAG LEFT</option>
                <option value="STAG RIGHT">STAG RIGHT</option>
                <option value="TRAIL">TRAIL</option>
                <option value="ECHELON LEFT">ECHELON LEFT</option>
                <option value="ECHELON RIGHT">ECHELON RIGHT</option>
                <option value="VEE">VEE</option>
                <option value="COMBAT CRUISE">COMBAT CRUISE</option>
                <option value="CBT CRUISE L">COMBAT CRUISE LEFT</option>
                <option value="CBT CRUISE R">COMBAT CRUISE RIGHT</option>
                <option value="CBT TRAIL">COMBAT TRAIL</option>
                <option value="CBT SPREAD">COMBAT SPREAD</option>
              </select>
            </div>

            <div className="input-group">
              <label>Takeoff Formation</label>
              <select
                name="to_formation"
                value={formData.to_formation}
                onChange={handleChange}
              >
                <option value="STAG LEFT">STAG LEFT</option>
                <option value="STAG RIGHT">STAG RIGHT</option>
                <option value="TRAIL">TRAIL</option>
                <option value="ECHELON LEFT">ECHELON LEFT</option>
                <option value="ECHELON RIGHT">ECHELON RIGHT</option>
                <option value="VEE">VEE</option>
                <option value="COMBAT CRUISE">COMBAT CRUISE</option>
                <option value="CBT CRUISE L">COMBAT CRUISE LEFT</option>
                <option value="CBT CRUISE R">COMBAT CRUISE RIGHT</option>
                <option value="CBT TRAIL">COMBAT TRAIL</option>
                <option value="CBT SPREAD">COMBAT SPREAD</option>
              </select>
            </div>

            <div className="input-group span-2">
              <label>Spacing</label>
              <select
                name="spacing"
                value={formData.spacing}
                onChange={handleChange}
              >
                <option value="N/A">N/A</option>
                <option value="TIGHT">TIGHT</option>
                <option value="CLOSE">CLOSE</option>
                <option value="LOOSE">LOOSE</option>
                <option value="EXT">EXTENDED</option>
              </select>
            </div>

            <div className="input-group">
              <label>Weapons Control Measure</label>
              <select
                name="weapons_control"
                value={formData.weapons_control}
                onChange={handleChange}
              >
                <option value="NONE">None</option>
                <option value="HOLD">WEAPONS HOLD</option>
                <option value="TIGHT">WEAPONS TIGHT</option>
                <option value="FREE">WEAPONS FREE</option>
              </select>
            </div>
            <div className="input-group">
              <label>Weapons Status</label>
              <select
                name="weapons_status"
                value={formData.weapons_status}
                onChange={handleChange}
              >
                <option value="NONE">None</option>
                <option value="ARMED">ARMED</option>
                <option value="LOADED">LOADED</option>
                <option value="STOWED">STOWED</option>
                <option value="CLEARED">CLEARED</option>
              </select>
            </div>

          </div>

          <div className="form-grid three-col-grid">
            <div className="input-group">
              <label>Go Around</label>
              <select
                name="go_around"
                value={formData.go_around}
                onChange={handleChange}
              >
                <option value="LEFT">Left</option>
                <option value="RIGHT">Right</option>
              </select>
            </div>
            <div className="input-group">
              <label>Door</label>
              <select name="door" value={formData.door} onChange={handleChange}>
                <option value="OPEN">Open</option>
                <option value="CLOSED">Closed</option>
              </select>
            </div>
            <div className="input-group">
              <label>Load</label>
              <select name="load" value={formData.load} onChange={handleChange}>
                <option value="LEFT">Left</option>
                <option value="RIGHT">Right</option>
              </select>
            </div>
          </div>

          <div className="form-grid two-col-grid">
            <div className="input-group span-2">
              <label>Remarks / Hazards</label>
              <textarea
                rows="8" 
                name="remarks"
                value={formData.remarks}
                onChange={handleChange}
                className="full-width"
                style={{ resize: "vertical" }} 
              />
            </div>
          </div>
          {/* --- NOTAM DROPDOWN --- */}
          {activeNotams && typeof activeNotams === "object" && allAvailableNotams.length > 0 && (
            <div>
              <p style={{ fontSize: "0.7rem", color: "#bbb", textTransform: "uppercase" }}>NOTAMS within a 10nm radius are included below for reference. You may select/deselect important or relevant NOTAMS as needed, and they will be included in REMARKS.</p>
            <div className="notam-section" style={{ marginTop: '10px', background: 'rgba(0,0,0,0.2)', padding: '8px', borderRadius: '4px' }}>
              
              {/* MASTER DROPDOWN TOGGLE */}
              <div 
                className="notam-header" 
                onClick={() => setShowNotamMenu(!showNotamMenu)}
                style={{ display: 'flex', justifyContent: 'space-between', cursor: 'pointer', fontWeight: 'bold', fontSize: '14px' }}
              >
                <span>Local NOTAMs ({allAvailableNotams.length})</span>
                <span>{showNotamMenu ? '▲' : '▼'}</span>
              </div>

              {/* EXPANDED MENU */}
              {showNotamMenu && (
                <div className="notam-content" style={{ marginTop: '10px', fontSize: '12px' }}>
                  
                  {/* SELECT ALL */}
                  <label style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px', fontWeight: 'bold', borderBottom: '1px solid #475569', paddingBottom: '5px' }}>
                    <input 
                      type="checkbox" 
                      checked={isAllSelected} 
                      onChange={handleToggleAllNotams} 
                    />
                    Select / Deselect All
                  </label>

                  {/* CATEGORIES */}
                  {Object.entries(activeNotams).map(([category, notams]) => (
                    <div key={category} style={{ marginBottom: '8px' }}>
                      <div 
                        onClick={() => toggleCategory(category)}
                        style={{ display: 'flex', justifyContent: 'space-between', cursor: 'pointer', background: 'rgba(255,255,255,0.05)', padding: '4px 8px', borderRadius: '4px' }}
                      >
                        <span style={{ color: '#38bdf8' }}>{category} ({notams.length})</span>
                        <span>{expandedCategories[category] ? '▲' : '▼'}</span>
                      </div>

                      {/* INDIVIDUAL NOTAMS */}
                      {expandedCategories[category] && (
                        <div style={{ padding: '8px', display: 'flex', flexDirection: 'column', gap: '6px' }}>
                          {notams.map((notam, idx) => (
                            <label key={idx} style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                              <input 
                                type="checkbox" 
                                style={{ marginTop: '3px' }}
                                checked={selectedNotams.includes(notam)}
                                onChange={() => handleToggleNotam(notam)}
                              />
                              <span style={{ lineHeight: '1.4', opacity: 0.9 }}>{notam}</span>
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}

                </div>
              )}
            </div>
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="cancel-btn" onClick={handleCloseModal}>
            Cancel
          </button>
          <button className="export-btn" onClick={handleSubmit}>
            Export Package
          </button>
        </div>
      </div>
    </Draggable>
  );
};

export default ExportModal;
