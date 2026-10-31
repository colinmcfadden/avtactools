import { useState } from "react";
import api from "../auth/api";

export const useSavedMaps = () => {
  const [history, setHistory] = useState([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  const [historyError, setHistoryError] = useState(null);

  const fetchHistory = async () => {
    setIsLoadingHistory(true);
    try {
      const res = await api.get("/lz");
      setHistory(res.data);
      setHistoryError(null);
    } catch (err) {
      // 401s are handled (sign-out) by the api interceptor; anything else is shown where the list is.
      if (err.response?.status !== 401) setHistoryError(err);
    } finally {
      setIsLoadingHistory(false);
    }
  };

  const saveMap = async (name, snapshot) => {
    const response = await api.post("/lz", { name, lz_data: snapshot });
    return response.data;
  };

  const loadMap = async (id) => {
    const res = await api.get(`/lz/${id}`);
    return res.data.lz_data;
  };

  /** `name` renames the saved record too; without it the name stays as it is. */
  const updateMap = async (id, snapshot, name) => {
    const response = await api.put(`/lz/${id}`, name ? { name, lz_data: snapshot } : { lz_data: snapshot });
    return response.data;
  };

  const deleteMap = async (id) => {
    await api.delete(`/lz/${id}`);
    setHistory((prev) => prev.filter((entry) => entry.id !== id));
  };

  return { history, isLoadingHistory, historyError, fetchHistory, saveMap, loadMap, updateMap, deleteMap };
};
