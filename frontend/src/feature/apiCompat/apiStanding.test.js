import { APP_TOO_OLD, BUILT_MAJORS, SERVER_TOO_OLD, SUPPORTED, servedMajors, standingWith } from "./apiStanding";

describe("the API this build talks", () => {
  it("is a list of whole majors", () => {
    expect(BUILT_MAJORS.length).toBeGreaterThan(0);
    expect(BUILT_MAJORS.every((major) => Number.isInteger(major) && major > 0)).toBe(true);
  });

  it("is supported by a server that serves one of its majors", () => {
    expect(standingWith([1], [1])).toBe(SUPPORTED);
    expect(standingWith([1, 2], [1])).toBe(SUPPORTED);
  });

  it("is too old for a server past all of its majors", () => {
    expect(standingWith([2], [1])).toBe(APP_TOO_OLD);
    expect(standingWith([1, 3], [2])).toBe(APP_TOO_OLD);
  });

  it("is ahead of a server behind all of its majors", () => {
    expect(standingWith([1], [2])).toBe(SERVER_TOO_OLD);
  });

  it("holds nothing against a server it knows nothing of", () => {
    expect(standingWith(null, [1])).toBe(SUPPORTED);
    expect(standingWith([], [1])).toBe(SUPPORTED);
  });
});

describe("the majors a config serves", () => {
  it("is its list, else its version's major, else 1 for a server from before the fields", () => {
    expect(servedMajors({ apiVersion: "2.1.0", apiMajors: [1, 2] })).toEqual([1, 2]);
    expect(servedMajors({ apiVersion: "3.0.0" })).toEqual([3]);
    expect(servedMajors({ configVersion: 1 })).toEqual([1]);
  });

  it("is nothing known for a version it cannot read, or no config", () => {
    expect(servedMajors({ apiVersion: "three" })).toBeNull();
    expect(servedMajors(null)).toBeNull();
  });
});
