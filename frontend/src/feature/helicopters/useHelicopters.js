import { useEffect, useMemo, useState } from "react";
import { getDistanceFeet } from "../../utils/Helpers";
import {
  FALLBACK_PROFILE,
  edgeGapFt,
  pairSeparation,
  profileForAsset,
} from "../aircraft/aircraftProfiles";

/**
 * Placed aircraft and their separation alerts.
 *
 * Each aircraft stores the profile it was placed with (`profileId`, a slug), so
 * a mixed serial — Black Hawks with a Chinook — is measured correctly: the gap
 * uses each aircraft's own rotor radius, and the requirement is the stricter of
 * the two platforms' tip clearances.
 *
 * Optional controlled-state shape:
 *   { helicopters: Helicopter[], setHelicopters: React.Dispatch<React.SetStateAction<Helicopter[]>> }
 */
export const useHelicopters = (targetLocation, options = {}) => {
  const [internalHelicopters, setInternalHelicopters] = useState([]);
  const [proximityAlerts, setProximityAlerts] = useState([]);
  const controlledHelicopters = options?.helicopters;
  const controlledSetHelicopters = options?.setHelicopters;
  const profiles = options?.profiles;
  const activeProfile = options?.activeProfile || FALLBACK_PROFILE;
  const isControlled =
    controlledHelicopters !== undefined &&
    typeof controlledSetHelicopters === "function";
  const helicopters = isControlled ? controlledHelicopters : internalHelicopters;
  const setHelicopters = isControlled
    ? controlledSetHelicopters
    : setInternalHelicopters;
  const helicopterList = useMemo(
    () => (Array.isArray(helicopters) ? helicopters : []),
    [helicopters],
  );
  const profileList = useMemo(
    () => (Array.isArray(profiles) ? profiles : []),
    [profiles],
  );

  const resolveProfile = useMemo(
    () => (helicopter) => profileForAsset(helicopter, profileList, activeProfile),
    [profileList, activeProfile],
  );

  const addHelo = () => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }

    let finalLat = targetLocation[0];
    let finalLon = targetLocation[1];
    let isClear = false;
    let attempts = 0;
    const offsetStep = 0.0001;

    // Nudge the new aircraft clear of anything already down, respecting the
    // separation each existing pair actually requires.
    while (!isClear && attempts < 50) {
      isClear = true;
      for (const helicopter of helicopterList) {
        const distance = getDistanceFeet(
          finalLat,
          finalLon,
          helicopter.lat,
          helicopter.lon,
        );
        const { minCenterDistanceFt } = pairSeparation(
          activeProfile,
          resolveProfile(helicopter),
        );
        if (distance < minCenterDistanceFt) {
          isClear = false;
          finalLon += offsetStep;
          break;
        }
      }
      attempts += 1;
    }

    const newHelo = {
      id: Date.now(),
      lat: finalLat,
      lon: finalLon,
      rotation: 0,
      type: "helo",
      // Recorded by slug so the aircraft keeps its identity across databases
      // and after the master list is renumbered.
      profileId: activeProfile?.slug || FALLBACK_PROFILE.slug,
    };

    setHelicopters((previous) => [
      ...(Array.isArray(previous) ? previous : []),
      newHelo,
    ]);
  };

  const updateHelicopter = (id, newProps) => {
    setHelicopters((previous) =>
      (Array.isArray(previous) ? previous : []).map((helicopter) =>
        helicopter.id === id ? { ...helicopter, ...newProps } : helicopter,
      ),
    );
  };

  const deleteHelicopter = (id) => {
    setHelicopters((previous) =>
      (Array.isArray(previous) ? previous : []).filter(
        (helicopter) => helicopter.id !== id,
      ),
    );
  };

  useEffect(() => {
    const alerts = [];

    for (let index = 0; index < helicopterList.length; index += 1) {
      for (
        let comparisonIndex = index + 1;
        comparisonIndex < helicopterList.length;
        comparisonIndex += 1
      ) {
        const firstHelicopter = helicopterList[index];
        const secondHelicopter = helicopterList[comparisonIndex];
        const firstProfile = resolveProfile(firstHelicopter);
        const secondProfile = resolveProfile(secondHelicopter);
        const centerDistance = getDistanceFeet(
          firstHelicopter.lat,
          firstHelicopter.lon,
          secondHelicopter.lat,
          secondHelicopter.lon,
        );
        const gap = edgeGapFt(centerDistance, firstProfile, secondProfile);
        const { requiredClearanceFt } = pairSeparation(firstProfile, secondProfile);

        if (gap < requiredClearanceFt) {
          const displayDistance = Math.max(0, Math.round(gap));
          // Name the platforms when they differ so it's obvious which
          // requirement is driving the alert.
          const pair =
            firstProfile.slug === secondProfile.slug
              ? firstProfile.designation
              : `${firstProfile.designation}/${secondProfile.designation}`;
          alerts.push({
            id: `${firstHelicopter.id}-${secondHelicopter.id}`,
            message:
              `Separation Alert (${pair}): Rotor edges are only ${displayDistance} ft apart ` +
              `(Min: ${Math.round(requiredClearanceFt)} ft / ` +
              `${Math.round(Math.max(firstProfile.rotor_tip_clearance_m, secondProfile.rotor_tip_clearance_m))} m).`,
          });
        }
      }
    }

    setProximityAlerts((previous) => {
      const unchanged =
        previous.length === alerts.length &&
        previous.every(
          (alert, index) =>
            alert.id === alerts[index].id &&
            alert.message === alerts[index].message,
        );

      return unchanged ? previous : alerts;
    });
  }, [helicopterList, resolveProfile]);

  return {
    helicopters: helicopterList,
    setHelicopters,
    addHelo,
    updateHelicopter,
    deleteHelicopter,
    proximityAlerts,
  };
};
