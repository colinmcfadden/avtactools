import { useState } from "react";

export const useGoAround = (targetLocation) => {
    const [goAround, setGoAround] = useState([]);

    const addGoAround = (direction) => {
    if (!targetLocation) {
      alert("Please search for a grid location first.");
      return;
    }
    const offset = 0.001;
    const newGA = {
      id: `ga-${Date.now()}`,
      lat:
        direction === "N"
          ? targetLocation[0] + offset
          : targetLocation[0] - offset,
      lon: targetLocation[1],
      direction: direction,
      rotation: 0,
    };
    setGoAround((prev) => [...prev, newGA]);
  };

  const updateGoAround = (id, newProps) => {
    setGoAround((prev) =>
      prev.map((ga) => (ga.id === id ? { ...ga, ...newProps } : ga)),
    );
  };

  const deleteGoAround = (id) => {
    setGoAround((prev) => prev.filter((ga) => ga.id !== id));
  };
  return { goAround, addGoAround, updateGoAround, deleteGoAround };
};