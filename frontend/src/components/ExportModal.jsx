import React, { useState, useEffect, useRef } from 'react'; 
import Draggable from 'react-draggable';
import './ExportModal.css'; // We will add CSS next

const ExportModal = ({ 
  isOpen, 
  onClose, 
  onExport, 
  mapData, // Object containing mgrs, latLong, elevation
  flightData // Object containing dh1 (takeoff), dh2 (land), goAround, etc.
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
    load: '',
    weapons_status: 'STOWED',
    remarks: ''
  });

  // Pre-fill data when the modal opens or data changes
  useEffect(() => {
    if (isOpen) {
      setFormData(prev => ({
        ...prev,
        mgrs_grid: mapData.mgrs || '',
        lat_long: mapData.latLong || '',
        elevation: mapData.elevation || '', // We will hook this up in App.js
        land_dir: flightData.dh2 || '', // Doghouse 2 is Landing
        takeoff_dir: flightData.dh1 || '', // Doghouse 1 is Takeoff
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
          {/* TOP SECTION: IDENTIFIERS */}
          <div className="form-row">
            <input name="lz_label" value={formData.lz_label} onChange={handleChange} placeholder="Label (LZ)" className="short-input" />
            <input name="lz_name" value={formData.lz_name} onChange={handleChange} placeholder="Name (HAWK)" className="long-input" />
          </div>

          {/* GRID DATA SECTION */}
          <div className="data-block">
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

          {/* TACTICAL DATA SECTION */}
          <div className="tactical-grid">
            <input name="call_sign" value={formData.call_sign} onChange={handleChange} placeholder="Call Sign" />
            <input name="freq" value={formData.freq} onChange={handleChange} placeholder="Freq" />
            <input name="formation" value={formData.formation} onChange={handleChange} placeholder="Formation" />
            
            <div className="split-row">
              <input name="land_dir" value={formData.land_dir} onChange={handleChange} placeholder="Land Dir" />
              <input name="takeoff_dir" value={formData.takeoff_dir} onChange={handleChange} placeholder="T/O Dir" />
            </div>

            <div className="split-row">
               <select name="go_around" value={formData.go_around} onChange={handleChange}>
                  <option value="LEFT">Left</option>
                  <option value="RIGHT">Right</option>
               </select>
               <input name="door" value={formData.door} onChange={handleChange} placeholder="Door" />
            </div>

            <input name="remarks" value={formData.remarks} onChange={handleChange} placeholder="Remarks / Hazards" className="full-width" />
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