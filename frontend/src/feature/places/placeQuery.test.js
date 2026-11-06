import { placeQueryOf } from "./placeQuery";
import { readTarget } from "../../utils/targetReading";

const kindOf = (text) => placeQueryOf(text, readTarget(text));

describe("placeQueryOf", () => {
  it("takes a name or a letter-led identifier for a place from two characters", () => {
    expect(kindOf("d")).toBe(null);
    expect(kindOf("dz")).toBe("place");
    expect(kindOf("DZJ")).toBe("place");
    expect(kindOf("katl")).toBe("place");
    expect(kindOf("Blairsville")).toBe("place");
    expect(kindOf("neel gap")).toBe("place");
    expect(kindOf("Peña")).toBe("place");
    expect(kindOf("  “Fort Novosel”")).toBe("place");
  });

  it("takes a digit-led identifier as a place or a grid being typed", () => {
    expect(kindOf("1GA3")).toBe("either");
    expect(kindOf("0J0")).toBe("either");
    expect(kindOf("16S")).toBe("either");
  });

  it("asks nothing for a grid or a coordinate, read or being typed", () => {
    expect(kindOf("16S GC 28864 55349")).toBe(null);
    expect(kindOf("16SGC2886455349")).toBe(null);
    expect(kindOf("16S GC")).toBe(null);
    expect(kindOf("34.5457, -84.1234")).toBe(null);
    expect(kindOf("34.5")).toBe(null);
    expect(kindOf("N34")).toBe(null);
    expect(kindOf("N 34° 32'")).toBe(null);
    expect(kindOf("W084")).toBe(null);
    expect(kindOf("3454")).toBe(null);
    expect(kindOf("")).toBe(null);
    expect(kindOf(" - ")).toBe(null);
  });

  it("still takes a name that starts with a hemisphere letter", () => {
    expect(kindOf("North Georgia")).toBe("place");
    expect(kindOf("Ellijay")).toBe("place");
    expect(kindOf("SW Georgia Regional")).toBe("place");
  });
});
