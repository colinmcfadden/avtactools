import JSZip from "jszip";
import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { DECLARES_DTD, NOT_XML } from "../../utils/xmlFiles";
import { INFLATES_TOO_FAR, LIMITS, NOT_KML, NOTHING_TO_DRAW, TOO_LARGE, imageInfo, leftOutWords, mainKmlIn, parseKmz, plainDescription } from "./parseKmz";
import { buildOverlayLayers } from "./KmzOverlayLayer";
import { featureAt } from "./overlayHits";

beforeAll(() => {
  if (typeof global.TextDecoder === "undefined") global.TextDecoder = NodeTextDecoder;
  if (typeof global.TextEncoder === "undefined") global.TextEncoder = NodeTextEncoder;
});

// Copied into this window's own ArrayBuffer: JSZip checks `instanceof ArrayBuffer`, and Node's is another realm's.
const fakeFile = (bytes, name, size = bytes.byteLength) => {
  const buffer = new Uint8Array(bytes).buffer;
  return { name, size, arrayBuffer: async () => buffer, slice: (from, to) => ({ arrayBuffer: async () => buffer.slice(from, to) }) };
};

const encode = (text) => new TextEncoder().encode(text);
const kml = (body, head = "") => `<?xml version="1.0"?>${head}<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><name>OBJ PEACH</name>${body}</Document></kml>`;
const kmlFile = (text, name = "peach.kml") => fakeFile(encode(text), name);
const zipped = async (entries) => {
  const zip = new JSZip();
  Object.entries(entries).forEach(([name, content]) => zip.file(name, content));
  return zip.generateAsync({ type: "uint8array", compression: "DEFLATE" });
};
const kmzFile = async (entries, name = "peach.kmz") => fakeFile(await zipped(entries), name);
const place = (geometry, more = "") => `<Placemark><name>HAWK</name>${more}${geometry}</Placemark>`;
const POINT = "<Point><coordinates>-84.25,34.5,0</coordinates></Point>";

// A PNG header: enough to be told what it is and how big.
const png = (width = 4, height = 4) => {
  const out = new Uint8Array(32);
  out.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  new DataView(out.buffer).setUint32(16, width);
  new DataView(out.buffer).setUint32(20, height);
  return out;
};
const jpeg = () => Uint8Array.from([0xff, 0xd8, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x00, 0x10, 0x00, 0x20, 0x03, 0, 0, 0, 0]);
const ground = (href, box = "<north>35</north><south>34</south><east>-83</east><west>-84</west>") =>
  `<GroundOverlay><name>IMAGERY</name><Icon><href>${href}</href></Icon><LatLonBox>${box}</LatLonBox></GroundOverlay>`;

/** Says every entry unpacks to `size` bytes, in both of the places a zip says so. */
const lieAboutSizes = (bytes, size) => {
  const out = new Uint8Array(bytes);
  const view = new DataView(out.buffer);
  for (let at = 0; at + 4 < out.length; at += 1) {
    const signature = view.getUint32(at, true);
    if (signature === 0x04034b50) view.setUint32(at + 22, size, true);
    if (signature === 0x02014b50) view.setUint32(at + 24, size, true);
  }
  return out;
};

const withLimit = async (name, value, run) => {
  const before = LIMITS[name];
  LIMITS[name] = value;
  try {
    return await run();
  } finally {
    LIMITS[name] = before;
  }
};

describe("what is drawn", () => {
  it("reads a point, with latitude and longitude the right way round", async () => {
    const doc = await parseKmz(kmlFile(kml(place(POINT))));
    expect(doc.name).toBe("OBJ PEACH");
    expect(doc.features).toEqual([expect.objectContaining({ id: "f0", kind: "point", name: "HAWK", at: [34.5, -84.25] })]);
    expect(doc.bounds).toEqual([[34.5, -84.25], [34.5, -84.25]]);
  });

  it("reads lines, rings, polygons with their holes, and several geometries in one placemark", async () => {
    const ring = (c) => `<LinearRing><coordinates>${c}</coordinates></LinearRing>`;
    const doc = await parseKmz(kmlFile(kml(`
      ${place("<LineString><coordinates>-84,34 -83.9,34.1</coordinates></LineString>")}
      ${place(ring("-84,34 -83,34 -83,35"))}
      ${place(`<Polygon><outerBoundaryIs>${ring("-84,34 -83,34 -83,35 -84,34")}</outerBoundaryIs><innerBoundaryIs>${ring("-83.6,34.2 -83.4,34.2 -83.4,34.4 -83.6,34.2")}</innerBoundaryIs></Polygon>`)}
      ${place(`<MultiGeometry>${POINT}<LineString><coordinates>-84,34 -83,34</coordinates></LineString></MultiGeometry>`)}
    `)));
    expect(doc.features.map((feature) => feature.kind)).toEqual(["line", "line", "polygon", "point", "line"]);
    expect(doc.features[2].rings).toHaveLength(2);
    expect(doc.summary).toBe("1 point · 3 lines · 1 area");
  });

  it("reads a gx:Track as a line", async () => {
    const doc = await parseKmz(kmlFile(kml(place("<gx:Track><gx:coord>-84 34 100</gx:coord><gx:coord>-83.9 34.1 120</gx:coord></gx:Track>"))));
    expect(doc.features[0]).toMatchObject({ kind: "line", path: [[34, -84], [34.1, -83.9]] });
  });

  it("goes into folders", async () => {
    const doc = await parseKmz(kmlFile(kml(`<Folder><Folder>${place(POINT)}</Folder></Folder>`)));
    expect(doc.features).toHaveLength(1);
  });

  it("names a file with no document name after the file", async () => {
    const doc = await parseKmz(kmlFile(`<kml xmlns="http://www.opengis.net/kml/2.2">${place(POINT)}</kml>`, "route check.kml"));
    expect(doc.name).toBe("route check");
  });
});

describe("styles", () => {
  const style = (id) => `<Style id="${id}"><LineStyle><color>800000ff</color><width>3</width></LineStyle><PolyStyle><color>4000ff00</color><fill>1</fill><outline>0</outline></PolyStyle><IconStyle><color>ffff0000</color></IconStyle></Style>`;
  const polygon = "<Polygon><outerBoundaryIs><LinearRing><coordinates>-84,34 -83,34 -83,35 -84,34</coordinates></LinearRing></outerBoundaryIs></Polygon>";

  it("reads KML's aabbggrr colours", async () => {
    const doc = await parseKmz(kmlFile(kml(`${style("s")}${place(polygon, "<styleUrl>#s</styleUrl>")}${place(POINT, "<styleUrl>#s</styleUrl>")}`)));
    expect(doc.features[0]).toMatchObject({ color: "#ff0000", width: 3, fillColor: "#00ff00", outline: false, fill: true });
    expect(doc.features[0].opacity).toBeCloseTo(128 / 255);
    expect(doc.features[0].fillOpacity).toBeCloseTo(64 / 255);
    expect(doc.features[1].color).toBe("#0000ff");
  });

  it("follows a style map to its normal style", async () => {
    const map = `<StyleMap id="m"><Pair><key>highlight</key><styleUrl>#h</styleUrl></Pair><Pair><key>normal</key><styleUrl>#s</styleUrl></Pair></StyleMap>`;
    const doc = await parseKmz(kmlFile(kml(`${style("s")}${map}${place("<LineString><coordinates>-84,34 -83,34</coordinates></LineString>", "<styleUrl>#m</styleUrl>")}`)));
    expect(doc.features[0].color).toBe("#ff0000");
  });

  it("lets a placemark's own style win over the one it names", async () => {
    const own = "<Style><LineStyle><color>ff00ffff</color></LineStyle></Style>";
    const doc = await parseKmz(kmlFile(kml(`${style("s")}${place("<LineString><coordinates>-84,34 -83,34</coordinates></LineString>", `<styleUrl>#s</styleUrl>${own}`)}`)));
    expect(doc.features[0]).toMatchObject({ color: "#ffff00", width: 3 });
  });

  it("never follows a style in another file", async () => {
    const doc = await parseKmz(kmlFile(kml(place(POINT, "<styleUrl>http://example.test/x.kml#s</styleUrl>"))));
    expect(doc.features[0].color).toBe("#ffffff");
  });

  it("does not hide the map under a polygon that states no style", async () => {
    const doc = await parseKmz(kmlFile(kml(place(polygon))));
    expect(doc.features[0].fillOpacity).toBeLessThan(1);
  });

  it("ignores a colour that is not one", async () => {
    const doc = await parseKmz(kmlFile(kml(`<Style id="s"><LineStyle><color>red;background:url(x)</color></LineStyle></Style>${place("<LineString><coordinates>-84,34 -83,34</coordinates></LineString>", "<styleUrl>#s</styleUrl>")}`)));
    expect(doc.features[0].color).toBe("#ffffff");
  });
});

describe("images", () => {
  it("draws a north-up PNG or JPEG in the archive, by what its bytes are", async () => {
    const doc = await parseKmz(await kmzFile({ "doc.kml": kml(ground("files/a.png") + ground("files/b.dat")), "files/a.png": png(), "files/b.dat": jpeg() }));
    expect(doc.images.map((image) => image.mime)).toEqual(["image/png", "image/jpeg"]);
    expect(doc.images[0].bounds).toEqual([[34, -84], [35, -83]]);
    expect(doc.summary).toBe("2 images");
  });

  it("finds an image relative to a KML that is not at the top", async () => {
    const doc = await parseKmz(await kmzFile({ "kml/main.kml": kml(ground("../img/a.png")), "img/a.png": png() }));
    expect(doc.images).toHaveLength(1);
  });

  it("finds an image whose name has had its case changed", async () => {
    const doc = await parseKmz(await kmzFile({ "doc.kml": kml(ground("FILES/A.PNG")), "files/a.png": png() }));
    expect(doc.images).toHaveLength(1);
  });

  it("never reaches for an image outside the file", async () => {
    const fetched = vi.spyOn(global, "fetch").mockRejectedValue(new Error("no"));
    const doc = await parseKmz(await kmzFile({ "doc.kml": kml(place(POINT) + ground("http://example.test/a.png") + ground("//example.test/a.png") + ground("file:///etc/passwd") + ground("data:image/png;base64,AAAA")) }));
    expect(doc.images).toHaveLength(0);
    expect(doc.leftOut.remoteImages).toBe(4);
    expect(fetched).not.toHaveBeenCalled();
    fetched.mockRestore();
  });

  it("does not leave the archive by a climbing path", async () => {
    const doc = await parseKmz(await kmzFile({ "doc.kml": kml(place(POINT) + ground("../../../etc/passwd")) }));
    expect(doc.leftOut.badImages).toBe(1);
  });

  it("refuses what is named a picture and is not", async () => {
    const doc = await parseKmz(await kmzFile({ "doc.kml": kml(place(POINT) + ground("a.png")), "a.png": "<svg onload=alert(1)>" }));
    expect(doc.images).toHaveLength(0);
    expect(doc.leftOut.badImages).toBe(1);
  });

  it("leaves out a rotated image and says so", async () => {
    const doc = await parseKmz(await kmzFile({
      "doc.kml": kml(place(POINT) + ground("a.png", "<north>35</north><south>34</south><east>-83</east><west>-84</west><rotation>30</rotation>") + `<GroundOverlay><Icon><href>a.png</href></Icon><gx:LatLonQuad><coordinates>-84,34 -83,34 -83,35 -84,35</coordinates></gx:LatLonQuad></GroundOverlay>`),
      "a.png": png(),
    }));
    expect(doc.leftOut.rotatedImages).toBe(2);
    expect(leftOutWords(doc.leftOut)[0]).toMatch(/rotated images/);
  });

  it("refuses a box that encloses nothing or is not on Earth", async () => {
    for (const box of ["<north>34</north><south>35</south><east>-83</east><west>-84</west>", "<north>95</north><south>34</south><east>-83</east><west>-84</west>", "<north>x</north><south>34</south><east>-83</east><west>-84</west>"]) {
      // eslint-disable-next-line no-await-in-loop
      const doc = await parseKmz(await kmzFile({ "doc.kml": kml(place(POINT) + ground("a.png", box)), "a.png": png() }));
      expect(doc.leftOut.badImages).toBe(1);
    }
  });

  it("will not ask a browser to allocate a huge picture its header claims", () => {
    expect(imageInfo(png(20000, 20000))).toBeNull();
    expect(imageInfo(png(0, 10))).toBeNull();
    expect(imageInfo(png(4000, 4000))).toMatchObject({ mime: "image/png", width: 4000 });
  });

  it("stops taking images past the limit", async () => {
    const doc = await withLimit("images", 1, async () => parseKmz(await kmzFile({ "doc.kml": kml(ground("a.png") + ground("a.png")), "a.png": png() })));
    expect(doc.images).toHaveLength(1);
    expect(doc.leftOut.badImages).toBe(1);
  });
});

describe("what is said about the rest", () => {
  it("counts what it leaves out", async () => {
    const doc = await parseKmz(kmlFile(kml(`${place(POINT)}<NetworkLink><Link><href>http://example.test/x.kml</href></Link></NetworkLink><ScreenOverlay/><PhotoOverlay/>${place("<Model/>")}`)));
    expect(doc.leftOut).toMatchObject({ networkLinks: 1, screenOverlays: 1, photoOverlays: 1, models: 1 });
    expect(leftOutWords(doc.leftOut)).toEqual(["1 network link", "1 3D model", "1 screen overlay", "1 photo overlay"]);
    expect(doc.summary).toBe("1 point · 4 left out");
  });

  it("says why a file came to nothing", async () => {
    await expect(parseKmz(kmlFile(kml("<NetworkLink/>")))).rejects.toThrow(`${NOTHING_TO_DRAW} It holds 1 network link.`);
    await expect(parseKmz(kmlFile(kml("")))).rejects.toThrow(NOTHING_TO_DRAW);
  });

  it("skips a coordinate that is no place, and draws the rest", async () => {
    const doc = await parseKmz(kmlFile(kml(place("<LineString><coordinates>-84,34 abc,def -200,34 -84,95 0x10,20 -83,34</coordinates></LineString>"))));
    expect(doc.features[0].path).toEqual([[34, -84], [34, -83]]);
    expect(doc.leftOut.unreadable).toBe(4);
  });

  it("does not make up a place for an empty point", async () => {
    await expect(parseKmz(kmlFile(kml(place("<Point><coordinates> </coordinates></Point>"))))).rejects.toThrow(NOTHING_TO_DRAW);
  });

  it("takes a description as words", () => {
    expect(plainDescription("<table><tr><td>Grid</td><td>16S</td></tr></table>")).toBe("Grid 16S");
    expect(plainDescription("<script>alert(1)</script>Ridge &amp; road<img src=x onerror=alert(1)>")).toBe("Ridge & road");
    expect(plainDescription("a<br>b")).toBe("a\nb");
  });
});

describe("hostile files", () => {
  it("refuses a document type and an entity by their text", async () => {
    await expect(parseKmz(kmlFile(kml(place(POINT), '<!DOCTYPE kml [<!ENTITY a "aaaa">]>')))).rejects.toThrow(DECLARES_DTD);
    await expect(parseKmz(await kmzFile({ "doc.kml": kml(place(POINT), "<!DOCTYPE kml>") }))).rejects.toThrow(DECLARES_DTD);
  });

  it("is not fooled by a declaration between a comment and a CDATA section", async () => {
    await expect(parseKmz(kmlFile(kml(place(POINT), '<!-- <![CDATA[ --><!DOCTYPE kml><!-- ]]> -->')))).rejects.toThrow(DECLARES_DTD);
  });

  it("reads a description that only quotes a document type", async () => {
    const doc = await parseKmz(kmlFile(kml(place(POINT, "<description><![CDATA[what <!DOCTYPE means]]></description>"))));
    expect(doc.features).toHaveLength(1);
  });

  it("refuses what is not XML, and XML that is not KML", async () => {
    await expect(parseKmz(kmlFile("<kml><unclosed>"))).rejects.toThrow(NOT_XML);
    await expect(parseKmz(kmlFile('<gpx version="1.1"/>', "route.kml"))).rejects.toThrow(NOT_KML);
  });

  it("refuses an archive holding no KML, and one that is damaged", async () => {
    await expect(parseKmz(await kmzFile({ "readme.txt": "hello" }))).rejects.toThrow(NOT_KML);
    await expect(parseKmz(fakeFile(Uint8Array.from([0x50, 0x4b, 1, 2, 3]), "bad.kmz"))).rejects.toThrow(/damaged/);
  });

  it("refuses a file too large before reading it", async () => {
    const file = fakeFile(encode(kml(place(POINT))), "big.kml", LIMITS.fileBytes + 1);
    file.arrayBuffer = vi.fn();
    await expect(parseKmz(file)).rejects.toThrow(TOO_LARGE);
    expect(file.arrayBuffer).not.toHaveBeenCalled();
  });

  it("counts what unpacks, whatever the archive says it will come to", async () => {
    // Highly compressible, and its headers claim a dozen bytes: only counting as it inflates can catch it.
    const bomb = lieAboutSizes(await zipped({ "doc.kml": kml(place(POINT), `<!-- ${"a".repeat(200000)} -->`) }), 12);
    await withLimit("inflatedBytes", 1000, async () => {
      await expect(parseKmz(fakeFile(bomb, "bomb.kmz"))).rejects.toThrow(INFLATES_TOO_FAR);
    });
  });

  it("counts the images against the same budget as the KML", async () => {
    const huge = new Uint8Array(4096);
    huge.set(png());
    const file = await kmzFile({ "doc.kml": kml(place(POINT) + ground("a.png")), "a.png": huge });
    await withLimit("inflatedBytes", 2048, async () => {
      await expect(parseKmz(file)).rejects.toThrow(INFLATES_TOO_FAR);
    });
  });

  it("refuses too many entries, placemarks or coordinates", async () => {
    const entries = { "doc.kml": kml(place(POINT)), "a.txt": "a", "b.txt": "b" };
    await withLimit("entries", 2, async () => {
      await expect(parseKmz(await kmzFile(entries))).rejects.toThrow(/too many files/);
    });
    await withLimit("features", 1, async () => {
      await expect(parseKmz(kmlFile(kml(place(POINT) + place(POINT))))).rejects.toThrow(/more than the map can draw/);
    });
    await withLimit("coordinates", 1, async () => {
      await expect(parseKmz(kmlFile(kml(place("<LineString><coordinates>-84,34 -83,34</coordinates></LineString>"))))).rejects.toThrow(/more than the map can draw/);
    });
  });

  it("refuses nesting deeper than a real file's", async () => {
    const depth = LIMITS.depth + 2;
    await expect(parseKmz(kmlFile(kml(`${"<Folder>".repeat(depth)}${place(POINT)}${"</Folder>".repeat(depth)}`)))).rejects.toThrow(NOT_KML);
    await expect(parseKmz(kmlFile(kml(place(`${"<MultiGeometry>".repeat(depth)}${POINT}${"</MultiGeometry>".repeat(depth)}`))))).rejects.toThrow(NOT_KML);
  });

  it("never gives Leaflet a file's words as HTML", async () => {
    const name = "<img src=x onerror=alert(1)>";
    const doc = await parseKmz(kmlFile(kml(`<Placemark><name>${name.replace(/</g, "&lt;")}</name>${POINT}</Placemark>`)));
    expect(doc.features[0].name).toBe(name);
    const { layers } = buildOverlayLayers(doc);
    const content = layers[0].getTooltip().getContent();
    expect(content).toBeInstanceOf(HTMLElement);
    expect(content.textContent).toBe(name);
    expect(content.querySelector("img")).toBeNull();
  });
});

describe("which KML a KMZ is about", () => {
  const zipOf = async (entries) => JSZip.loadAsync(new Uint8Array(await zipped(entries)).buffer);

  it("is doc.kml, whatever else is there", async () => {
    expect(mainKmlIn(await zipOf({ "a.kml": "x", "doc.kml": "x" }))).toBe("doc.kml");
  });

  it("is otherwise the shallowest", async () => {
    expect(mainKmlIn(await zipOf({ "deep/down/a.kml": "x", "top.kml": "x" }))).toBe("top.kml");
  });

  it("is none where there is none", async () => {
    expect(mainKmlIn(await zipOf({ "a.txt": "x" }))).toBeNull();
  });
});

describe("what a click on the map is on", () => {
  const project = (lat, lon) => ({ x: lon * 100, y: -lat * 100 });
  const point = { kind: "point", name: "P", at: [0, 0], bbox: [0, 0, 0, 0] };
  const line = { kind: "line", name: "L", path: [[0, -1], [0, 1]], bbox: [0, -1, 0, 1] };
  const square = [[-1, -1], [-1, 1], [1, 1], [1, -1], [-1, -1]];
  const area = { kind: "polygon", name: "A", rings: [square], bbox: [-1, -1, 1, 1] };
  const overlay = (...features) => ({ doc: { features } });

  it("finds a point within reach and not beyond it", () => {
    expect(featureAt([overlay(point)], { x: 10, y: 0 }, project)).toBe(point);
    expect(featureAt([overlay(point)], { x: 13, y: 0 }, project)).toBeNull();
  });

  it("prefers a point to the line under it", () => {
    expect(featureAt([overlay(line, point)], { x: 2, y: 0 }, project)).toBe(point);
  });

  it("answers for a polygon on its edge only, so a shaded area does not answer every click", () => {
    expect(featureAt([overlay(area)], { x: 0, y: 0 }, project)).toBeNull();
    expect(featureAt([overlay(area)], { x: 98, y: 0 }, project)).toBe(area);
  });

  it("prefers the overlay drawn last", () => {
    const other = { ...point, name: "Q" };
    expect(featureAt([overlay(point), overlay(other)], { x: 0, y: 0 }, project)).toBe(other);
  });
});
