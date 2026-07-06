import { useState } from "react";
import api from "../auth/api";

export const useSavedMaps = () => {
  const [history, setHistory] = useState([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  const fetchHistory = async () => {
    setIsLoadingHistory(true);
    try {
      const res = await api.get("/lz");
      setHistory(res.data);
    } catch (err) {
      // 401s are handled (alert + sign-out) by the api interceptor.
      if (err.response?.status !== 401) {
        alert("Couldn't load saved maps: " + err.message);
      }
    } finally {
      setIsLoadingHistory(false);
    }
  };

  const saveMap = async (name, snapshot) => {
    await api.post("/lz", { name, lz_data: snapshot });
  };

  const loadMap = async (id) => {
    const res = await api.get(`/lz/${id}`);
    return res.data.lz_data;
  };

  const updateMap = async (id, snapshot) => {
    await api.put(`/lz/${id}`, { lz_data: snapshot });
  };

  const deleteMap = async (id) => {
    await api.delete(`/lz/${id}`);
    setHistory((prev) => prev.filter((entry) => entry.id !== id));
  };

  return { history, isLoadingHistory, fetchHistory, saveMap, loadMap, updateMap, deleteMap };
};
