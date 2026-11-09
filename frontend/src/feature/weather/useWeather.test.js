import { act, renderHook } from "@testing-library/react";
import api from "../auth/api";
import { sameWeatherSpot, slopeWindFor, useWeather } from "./useWeather";

vi.mock("../auth/api", () => ({
  __esModule: true,
  default: { get: vi.fn() },
}));

const report = (station, speed, dir) => ({ data: { station_id: station, wind_spd_kts: speed, wind_dir: dir, notams: { Clear: [] } } });

const deferred = () => {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
};

describe("the weather of an LZ/PZ", () => {
  it("keeps the wind the slope is judged by, and where the report is for", async () => {
    api.get.mockResolvedValue(report("KRYY", 12, 270));
    const { result } = renderHook(() => useWeather());

    await act(() => result.current.fetchWeather(34.5, -84.1));

    expect(result.current.winds.at).toEqual([34.5, -84.1]);
    expect(slopeWindFor(result.current.winds, [34.5, -84.1])).toEqual({ speedKts: 12, fromDeg: 270, variable: false });
    expect(slopeWindFor(result.current.winds, [35.2, -83.4])).toBeNull();          // another LZ/PZ's report is no report for it
  });

  it("shows only the latest place's answer when an earlier one comes after it", async () => {
    const first = deferred();
    const second = deferred();
    api.get.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useWeather());

    let a;
    let b;
    act(() => { a = result.current.fetchWeather(34.5, -84.1); });
    act(() => { b = result.current.fetchWeather(35.2, -83.4); });
    await act(async () => { second.resolve(report("KAVL", 6, 180)); await b; });
    await act(async () => { first.resolve(report("KRYY", 12, 270)); await a; });

    expect(result.current.winds.station).toBe("KAVL");
    expect(result.current.winds.at).toEqual([35.2, -83.4]);
    expect(result.current.loadingWeather).toBe(false);
  });

  it("is fetched once for a place however often it is made sure of", async () => {
    api.get.mockResolvedValue(report("KRYY", 12, 270));
    const { result } = renderHook(() => useWeather());

    await act(async () => { result.current.ensureWeather(34.5, -84.1); });
    await act(async () => { result.current.ensureWeather(34.5001, -84.1001); });   // the same LZ/PZ
    expect(api.get).toHaveBeenCalledTimes(1);

    await act(async () => { result.current.ensureWeather(35.2, -83.4); });
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it("is the same place within about a kilometre", () => {
    expect(sameWeatherSpot([34.5, -84.1], [34.505, -84.1])).toBe(true);           // 560 m
    expect(sameWeatherSpot([34.5, -84.1], [34.52, -84.1])).toBe(false);           // 2.2 km
    expect(sameWeatherSpot(null, [34.5, -84.1])).toBe(false);
  });
});
