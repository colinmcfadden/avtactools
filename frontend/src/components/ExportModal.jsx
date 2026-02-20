import React, { useState, useEffect, useRef } from 'react';
import Draggable from 'react-draggable';
// Make sure this import path matches your actual CSS file location
import './ExportModal.css'; 

const ExportModal = ({ 
  isOpen, 
  onClose, 
  onExport, 
  mapData, 
  flightData 
}) => {

  const nodeRef = useRef(null);

  const [formData, setFormData] = useState({
    lz_label: 'LZ',
    lz_name: 'HAWK',
    objective: '',
    mgrs_grid: '',
    lat_long: '',
    elevation: '',
    call_sign: '',
    freq: '',
    formation: 'STAG RIGHT',
    land_dir: '',
    go_around: 'LEFT',
    takeoff_dir: '',
    door: 'RIGHT',
    load: 'LEFT',
    weapons_status: 'STOWED',
    remarks: ''
  });

  // Pre-fill data when the modal opens or data changes
  useEffect(() => {
    if (isOpen) {
      setFormData(prev => ({
        ...prev,
        mgrs_grid: mapData.mgrs ? mapData.mgrs.replace(/^(.{3})(.{2})(.{4})(.{4})$/, '$1 $2 $3 $4') : '',
        lat_long: mapData.latLong || '',
        elevation: mapData.elevation + "'" || '', 
        land_dir: flightData.landing_hdg || '', 
        takeoff_dir: flightData.takeoff_hdg || '', 
        go_around: flightData.goAround || 'LEFT'
      }));
    }
  }, [isOpen, mapData, flightData]);

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const handleSubmit = () => {
    onExport(formData);
  };

  if (!isOpen) return null;

  return (
    <Draggable nodeRef={nodeRef} handle=".modal-header">
      <div ref={nodeRef} className="export-modal-container glass-panel">
        <div className="modal-header">
          <h3>Export LZ Card</h3>
          <button className="close-btn" onClick={onClose}>×</button>
        </div>

        <div className="modal-body">
          
          {/* SECTION 1: IDENTIFIERS (Grid with different column sizes) */}
          <div className="form-grid header-grid">
            <div className="input-group">
              <label>Type</label>
              <select name="lz_label" value={formData.lz_label} onChange={handleChange}>
                <option value="LZ">LZ</option>
                <option value="PZ">PZ</option>
              </select>
            </div>
            <div className="input-group span-flex">
              <label>Name</label>
              <input name="lz_name" value={formData.lz_name} onChange={handleChange} />
            </div>
          </div>

          <div className="form-divider">Grid Data</div>

          {/* SECTION 2: 2x2 GRID */}
          <div className="form-grid two-col-grid">
            <div className="input-group">
              <label>Objective</label>
              <input name="objective" value={formData.objective} onChange={handleChange} />
            </div>
            <div className="input-group">
              <label>MGRS Grid</label>
              <input name="mgrs_grid" value={formData.mgrs_grid} onChange={handleChange} />
            </div>
            <div className="input-group">
              <label>Lat Long</label>
              <input name="lat_long" value={formData.lat_long} onChange={handleChange} />
            </div>
            <div className="input-group">
              <label>Elevation</label>
              <input name="elevation" value={formData.elevation} onChange={handleChange} />
            </div>
          </div>

          <div className="form-divider">Tactical Data</div>

          {/* SECTION 3: TACTICAL GRID */}
          <div className="form-grid two-col-grid">
            
            <div className="input-group">
              <label>Call Sign</label>
              <input name="call_sign" value={formData.call_sign} onChange={handleChange} />
            </div>
            <div className="input-group">
              <label>Freq</label>
              <input name="freq" value={formData.freq} onChange={handleChange} />
            </div>

            <div className="input-group span-2">
              <label>Formation</label>
              <select name="formation" value={formData.formation} onChange={handleChange}>
                <option value="STAG LEFT">STAG LEFT</option>
                <option value="STAG RIGHT">STAG RIGHT</option>
                <option value="TRAIL">TRAIL</option>
              </select>
            </div>
            
            <div className="input-group">
              <label>Land Dir</label>
              <input name="land_dir" value={formData.land_dir} onChange={handleChange} />
            </div>
            <div className="input-group">
              <label>T/O Dir</label>
              <input name="takeoff_dir" value={formData.takeoff_dir} onChange={handleChange} />
            </div>
          </div>

          <div className="form-grid three-col-grid">
             <div className="input-group">
               <label>Go Around</label>
               <select name="go_around" value={formData.go_around} onChange={handleChange}>
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
                <textarea rows="2" name="remarks" value={formData.remarks} onChange={handleChange} className="full-width" style={{resize: 'none'}}/>
              </div>
            </div>
        </div>

        <div className="modal-footer">
          <button className="cancel-btn" onClick={onClose}>Cancel</button>
          <button className="export-btn" onClick={handleSubmit}>Export Package</button>
        </div>
      </div>
    </Draggable>
  );
};

export default ExportModal;