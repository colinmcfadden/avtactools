import React, { useState, useMemo, useRef, useEffect } from "react";
import { Polygon, Marker } from "react-leaflet";
import L from "leaflet";
import { createMapObjectHandleIcon } from "../../utils/mapObjectControls";

const SectorMarker = ({
  data,
  updateSectorPoint,
  moveSector,
  deleteSector,
}) => {
  const [isActive, setIsActive] = useState(false); // Controls visibility
  const isDragging = useRef(false); // Tracks if we are currently dragging

  // Direct access to Leaflet instances (Bypasses React State for smooth dragging)
  const polygonRef = useRef(null);
  const centerRef = useRef(null);
  const cornerRefs = useRef([]);

  // Calculate Centroid
  const getCentroid = (points) => {
    const lat = (points[0].lat + points[1].lat + points[2].lat) / 3;
    const lng = (points[0].lng + points[1].lng + points[2].lng) / 3;
    return { lat, lng };
  };

  const center = getCentroid(data.points);
  const positions = data.points.map((p) => [p.lat, p.lng]);

  // 3. EVENT HANDLERS

  // -- VISIBILITY LOGIC --
  const onMouseEnter = () => setIsActive(true);
  const onMouseLeave = () => {
    if (!isDragging.current) setIsActive(false);
  };

  // -- CENTER DRAG (Moves the whole shape) --
  const centerDragHandlers = useMemo(
    () => ({
      dragstart: (e) => {
        isDragging.current = true;
        setIsActive(true);

        // Calculate offsets of all corners relative to the center
        const startCenter = e.target.getLatLng();
        e.target.dragOffsets = data.points.map((p) => ({
          lat: p.lat - startCenter.lat,
          lng: p.lng - startCenter.lng,
        }));
      },
      drag: (e) => {
        const newCenter = e.target.getLatLng();
        const offsets = e.target.dragOffsets;

        // 1. Calculate new corner positions
        const newPoints = offsets.map((offset) => ({
          lat: newCenter.lat + offset.lat,
          lng: newCenter.lng + offset.lng,
        }));

        // 2. Update Polygon Visuals DIRECTLY (No React Render)
        if (polygonRef.current) {
          polygonRef.current.setLatLngs(newPoints);
        }

        // 3. Update Corner Markers DIRECTLY
        newPoints.forEach((p, index) => {
          if (cornerRefs.current[index]) {
            cornerRefs.current[index].setLatLng(p);
          }
        });
      },
      dragend: (e) => {
        isDragging.current = false;
        // Finalize: Calculate the total delta to update React State
        const { lat, lng } = e.target.getLatLng();
        const dLat = lat - center.lat;
        const dLon = lng - center.lng;
        moveSector(data.id, dLat, dLon);
      },
    }),
    [data, center, moveSector],
  );

  // -- CORNER DRAG (Moves one point) --
  const createCornerHandler = (index) => ({
    dragstart: () => {
      isDragging.current = true;
      setIsActive(true);
    },
    drag: (e) => {
      const newLatLng = e.target.getLatLng();

      // 1. Get current points from the prop (or ref)
      // We use a temp array to calculate the new visual state
      const currentPoints = polygonRef.current.getLatLngs()[0]; // Leaflet returns nested array

      // 2. Update the specific point in the temp array
      currentPoints[index] = newLatLng;

      // 3. Redraw Polygon
      polygonRef.current.setLatLngs(currentPoints);

      // 4. Redraw Center Marker (It must move if the shape changes)
      // Recalculate centroid based on the new corner position
      let sumLat = 0,
        sumLng = 0;
      currentPoints.forEach((p) => {
        sumLat += p.lat;
        sumLng += p.lng;
      });
      if (centerRef.current) {
        centerRef.current.setLatLng([sumLat / 3, sumLng / 3]);
      }
    },
    dragend: (e) => {
      isDragging.current = false;
      const { lat, lng } = e.target.getLatLng();
      updateSectorPoint(data.id, index, lat, lng);
    },
  });

  // 4. ICONS
  const cornerIcon = createMapObjectHandleIcon({
    type: "resize",
    title: "Drag to reshape sector",
    tone: "violet",
    size: 26,
    className: "sector-corner",
  });

  const moveIcon = createMapObjectHandleIcon({
    type: "move",
    title: "Drag to move sector",
    tone: "violet",
    size: 32,
    className: "sector-move",
  });

  return (
    <>
      {/* POLYGON: Always Visible, Handles Hover Detection */}
      <Polygon
        ref={polygonRef}
        positions={positions}
        pathOptions={{
          color: "#9370DB",
          fillColor: "#9370DB",
          fillOpacity: 0.2,
          weight: 1.5,
        }}
        eventHandlers={{
          mouseover: onMouseEnter,
          mouseout: onMouseLeave,
          click: (e) => {
            L.DomEvent.stopPropagation(e);
            setIsActive((active) => !active);
          },
          contextmenu: (e) => {
            L.DomEvent.stopPropagation(e);
            if (window.confirm("Delete Sector of Fire?")) deleteSector(data.id);
          },
        }}
      />

      {/* DRAG HANDLES: Visibility controlled by opacity */}
      <div style={{ opacity: isActive ? 1 : 0, transition: "opacity 0.2s" }}>
        {/* Center Marker */}
        <Marker
          ref={centerRef}
          position={[center.lat, center.lng]}
          icon={moveIcon}
          draggable={true}
          eventHandlers={{
            ...centerDragHandlers,
            mouseover: onMouseEnter,
            mouseout: onMouseLeave,
          }}
          zIndexOffset={1000}
          opacity={isActive ? 1 : 0} // Leaflet opacity
        />

        {/* Corner Markers */}
        {data.points.map((point, index) => (
          <Marker
            key={`${data.id}-p${index}`}
            ref={(el) => (cornerRefs.current[index] = el)}
            position={[point.lat, point.lng]}
            icon={cornerIcon}
            draggable={true}
            eventHandlers={{
              ...createCornerHandler(index),
              mouseover: onMouseEnter,
              mouseout: onMouseLeave,
            }}
            zIndexOffset={1001}
            opacity={isActive ? 1 : 0} // Leaflet opacity
          />
        ))}
      </div>
    </>
  );
};

export default SectorMarker;
