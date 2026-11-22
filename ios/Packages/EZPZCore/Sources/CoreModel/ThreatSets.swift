import CoreJS

/// One threat of a saved set. `mask` is its terrain mask as the web keeps it (`{bounds, radars, for}`), whole, or nil for none;
/// `extras` are the fields of the saved threat this version does not know (the web's `color`, whatever a newer release adds), and
/// `rawRadars` its radars as they were read, written back while nothing has changed them so a field a newer release put on a radar
/// survives.
public struct StoredThreat: Equatable, Sendable {
    public var entry: ThreatEntry
    public var mask: JSONObject?
    public var extras: JSONObject
    public var rawRadars: JSONArray?

    public init(entry: ThreatEntry, mask: JSONObject? = nil, extras: JSONObject = JSONObject(), rawRadars: JSONArray? = nil) {
        self.entry = entry
        self.mask = mask
        self.extras = extras
        self.rawRadars = rawRadars
    }

    // By hand because JSONObject and JSONArray have no Equatable of their own: structurally, as Kotlin's data class compares them.
    public static func == (a: StoredThreat, b: StoredThreat) -> Bool {
        a.entry == b.entry && a.mask.map(JSONTree.object) == b.mask.map(JSONTree.object) && JSONTree.object(a.extras) == .object(b.extras)
            && a.rawRadars.map(JSONTree.array) == b.rawRadars.map(JSONTree.array)
    }
}

/// A set of threats as it is read from the Library. `unreadable` holds what could not be read as a threat, to be written back after
/// the others rather than lost by the next save; `version` and `extras` are the document's own, kept as found.
public struct ThreatSetDocument: Equatable, Sendable {
    public var threats: [StoredThreat]
    public var unreadable: [JSONTree]
    public var version: JSONTree
    public var extras: JSONObject

    public init(
        threats: [StoredThreat] = [],
        unreadable: [JSONTree] = [],
        version: JSONTree = .number(Double(ThreatSets.documentVersion)),
        extras: JSONObject = JSONObject()
    ) {
        self.threats = threats
        self.unreadable = unreadable
        self.version = version
        self.extras = extras
    }

    public static func == (a: ThreatSetDocument, b: ThreatSetDocument) -> Bool {
        a.threats == b.threats && a.unreadable == b.unreadable && a.version == b.version && JSONTree.object(a.extras) == .object(b.extras)
    }
}

/// A set of threats as the Library keeps it: the web's `{version: 1, threats: [...]}` (`threatSets.js`), read and written the same
/// way and held to `contracts/fixtures/threats/sets.json`. A terrain mask is stored with its threat and with what it was worked out
/// for (`for`), so one that no longer matches its threat's place or radars is never drawn or kept.
///
/// **Not like the web**, each with a test: a threat the types here cannot hold (a radar with a band missing a number, a name that is
/// not text) cannot be read and is written back as it came, where the web would carry it; a threat given a new id gets the same one
/// each time its document is read (`read`'s `freshPrefix`), where the web's come from the clock.
public enum ThreatSets {
    public static let documentVersion = 1
    public static let firstSetName = "THREATS"
    public static let maxName = 100

    /// What a set's masks may come to: the server takes 5 MB a set, and past this a mask is left out and worked out again when it is
    /// wanted.
    public static let maskBudget = 4 * 1024 * 1024

    private static let known = Set(
        [
            "id", "name", "milstdId", "lat", "lon", "information", "source", "showThreat", "visible", "radars", "mask", "maskLoading",
            "maskError",
        ].map(UTF16Key.init)
    )

    /// A saved document read for the map. `usedIds` are the threat ids other open sets hold (a duplicate opened beside its original
    /// has the same ones, and every edit finds its threat by id). A threat with no id of its own, or one already taken, is called
    /// `<freshPrefix>-<place>`, so reading the document again gives it the same one.
    public static func read(_ data: JSONObject, usedIds: Set<String> = [], freshPrefix: String = "threat-read") -> ThreatSetDocument {
        let list = data["threats"]?.arrayValue
        var seen = Set(usedIds.map(UTF16Key.init))
        var threats: [StoredThreat] = []
        var unreadable: [JSONTree] = []
        for (place, element) in (list?.elements ?? []).enumerated() {
            if let read = readThreat(element, place, &seen, freshPrefix) { threats.append(read) } else { unreadable.append(element) }
        }
        let version: JSONTree = if let v = data["version"], !v.isNull { v } else { .number(Double(documentVersion)) }
        return ThreatSetDocument(
            threats: threats, unreadable: unreadable, version: version, extras: data.removing("threats").removing("version"))
    }

    private static func readThreat(_ element: JSONTree, _ place: Int, _ seen: inout Set<UTF16Key>, _ freshPrefix: String) -> StoredThreat? {
        guard let saved = element.objectValue,
            let lat = number(saved, "lat"),
            let lon = number(saved, "lon"),
            let raw = saved["radars"]?.arrayValue,
            let radars = radarsOf(.array(raw)),
            let name = text(saved, "name", Radars.defaultName),
            let milstdId = text(saved, "milstdId", Radars.defaultMilstdId),
            let information = text(saved, "information", ""),
            let source = text(saved, "source", Radars.defaultSource),
            let showThreat = flag(saved, "showThreat", default: true)
        else { return nil }
        let threat = Threat(
            name: name, milstdId: milstdId, lat: lat, lon: lon, information: information, source: source, showThreat: showThreat,
            radars: radars
        )
        let own: String? = if let s = saved["id"]?.stringValue, !s.isEmpty, !seen.contains(UTF16Key(s)) { s } else { nil }
        let id = own ?? fresh(freshPrefix, place, seen)
        seen.insert(UTF16Key(id))
        // kotlinx's `booleanOrNull` reads a primitive's text, so a quoted "false" hides the threat too.
        let hidden = saved["visible"].map { $0.arrayValue == nil && $0.objectValue == nil && ModelJSON.content($0) == "false" } ?? false
        let entry = ThreatEntry(id: id, threat: threat, visible: !hidden)
        let mask = saved["mask"]?.objectValue.flatMap { maskFits(threat, $0) ? $0 : nil }
        let extras = JSONObject(saved.entries.filter { !known.contains(UTF16Key($0.key)) }.map { ($0.key, $0.value) })
        return StoredThreat(entry: entry, mask: mask, extras: extras, rawRadars: raw)
    }

    private static func fresh(_ prefix: String, _ place: Int, _ seen: Set<UTF16Key>) -> String {
        var n = place
        while true {
            let id = "\(prefix)-\(n)"
            if !seen.contains(UTF16Key(id)) { return id }
            n += 1
        }
    }

    /// The radars of a saved threat, or nil when the types here cannot hold them.
    public static func radarsOf(_ element: JSONTree) -> [Radar]? {
        guard let list = element.arrayValue else { return nil }
        var radars: [Radar] = []
        for item in list.elements {
            guard let radar = radarOf(item) else { return nil }
            radars.append(radar)
        }
        return radars
    }

    private static func radarOf(_ element: JSONTree) -> Radar? {
        guard let radar = element.objectValue, let rawBands = radar["bands"]?.arrayValue else { return nil }
        var bands: [RadarBand] = []
        for item in rawBands.elements {
            guard let band = bandOf(item) else { return nil }
            bands.append(band)
        }
        guard let type = whole(radar, "type"),
            let rangeNmi = number(radar, "rangeNmi"),
            let antennaHeightFt = number(radar, "antennaHeightFt"),
            let aglNotMsl = flag(radar, "aglNotMsl"),
            let showMask = flag(radar, "showMask"),
            let showRangeRings = flag(radar, "showRangeRings")
        else { return nil }
        return Radar(
            type: type, rangeNmi: rangeNmi, antennaHeightFt: antennaHeightFt, aglNotMsl: aglNotMsl, showMask: showMask,
            showRangeRings: showRangeRings, bands: bands
        )
    }

    private static func bandOf(_ element: JSONTree) -> RadarBand? {
        guard let band = element.objectValue,
            let altFt = number(band, "altFt"),
            let color = band["color"]?.stringValue,
            let alpha = number(band, "alpha"),
            let colorIndex = whole(band, "colorIndex"),
            let viewable = flag(band, "viewable")
        else { return nil }
        return RadarBand(altFt: altFt, color: color, alpha: alpha, colorIndex: colorIndex, viewable: viewable)
    }

    /// A finite JSON number; text, even numeric text, is none.
    private static func number(_ object: JSONObject, _ key: String) -> Double? {
        guard case .number(let n)? = object[key], n.value.isFinite else { return nil }
        return n.value
    }

    private static func whole(_ object: JSONObject, _ key: String) -> Int? {
        guard let value = number(object, key), value == value.rounded(.toNearestOrEven), abs(value) < Double(Int32.max) else { return nil }
        return Int(value)
    }

    /// A flag, or nil when it is there and is not one. Absent is `default`, or nil with none.
    private static func flag(_ object: JSONObject, _ key: String, default fallback: Bool? = nil) -> Bool? {
        guard let value = object[key] else { return fallback }
        return value.boolValue
    }

    /// Text, or `default` when it is absent, or nil when it is there and is not text.
    private static func text(_ object: JSONObject, _ key: String, _ fallback: String) -> String? {
        guard let value = object[key] else { return fallback }
        return value.stringValue
    }

    /// The document saved for a set: every threat, and each mask while the document stays within `maskBudget`.
    ///
    /// A mask's size is its compact JSON's length in UTF-16 units, as Kotlin's `toString().length` measures it.
    public static func write(_ document: ThreatSetDocument, maskBudget: Int = ThreatSets.maskBudget) -> JSONObject {
        var room = maskBudget
        let written = document.threats.map { stored -> JSONTree in
            let mask = stored.mask.flatMap { maskFits(stored.entry.threat, $0) ? $0 : nil }
            let size = mask.map { JSONTree.object($0).serialized.utf16.count } ?? 0
            if let mask, size <= room {
                room -= size
                return .object(threatJson(stored, mask))
            }
            return .object(threatJson(stored, nil))
        }
        var entries = document.extras.entries.map { ($0.key, $0.value) }
        entries.append(("version", document.version))
        entries.append(("threats", .array(written + document.unreadable)))
        return JSONObject(entries)
    }

    private static func threatJson(_ stored: StoredThreat, _ mask: JSONObject?) -> JSONObject {
        let threat = stored.entry.threat
        let raw = stored.rawRadars.flatMap { radarsOf(.array($0)) == threat.radars ? $0 : nil }
        var fields: [(String, JSONTree)] = [
            ("id", .string(stored.entry.id)),
            ("name", .string(threat.name)),
            ("milstdId", .string(threat.milstdId)),
            ("lat", number(threat.lat)),
            ("lon", number(threat.lon)),
            ("information", .string(threat.information)),
            ("source", .string(threat.source)),
            ("showThreat", .bool(threat.showThreat)),
            ("visible", .bool(stored.entry.visible)),
            ("radars", raw.map(JSONTree.array) ?? radarsJson(threat.radars)),
        ]
        fields += stored.extras.entries.map { ($0.key, $0.value) }
        if let mask { fields.append(("mask", .object(mask))) }
        return JSONObject(fields)
    }

    private static func radarsJson(_ radars: [Radar]) -> JSONTree {
        .array(
            radars.map { radar in
                .object(
                    JSONObject([
                        ("type", number(Double(radar.type))),
                        ("rangeNmi", number(radar.rangeNmi)),
                        ("antennaHeightFt", number(radar.antennaHeightFt)),
                        ("aglNotMsl", .bool(radar.aglNotMsl)),
                        ("showMask", .bool(radar.showMask)),
                        ("showRangeRings", .bool(radar.showRangeRings)),
                        (
                            "bands",
                            .array(
                                radar.bands.map { band in
                                    .object(
                                        JSONObject([
                                            ("altFt", number(band.altFt)),
                                            ("color", .string(band.color)),
                                            ("alpha", number(band.alpha)),
                                            ("colorIndex", number(Double(band.colorIndex))),
                                            ("viewable", .bool(band.viewable)),
                                        ])
                                    )
                                }
                            )
                        ),
                    ])
                )
            }
        )
    }

    /// A number as JavaScript writes it, so `50.0` is `50` in the document as it is in the web's; null for none.
    private static func number(_ value: Double) -> JSONTree { value.isFinite ? .number(value) : .null }

    /// What a terrain mask depends on: where the threat is and what its radars are.
    public static func subjectOf(_ threat: Threat) -> JSONObject {
        JSONObject([("lat", number(threat.lat)), ("lon", number(threat.lon)), ("radars", radarsJson(threat.radars))])
    }

    /// Whether `mask` is still the mask of `threat`: the same place and the same radars.
    public static func maskFits(_ threat: Threat, _ mask: JSONObject?) -> Bool {
        guard let subject = mask?["for"] else { return false }
        return sameValue(subject, .object(subjectOf(threat)))
    }

    /// Equal as values, whatever order their keys are in and however a number is spelt (the API answers with its keys sorted).
    public static func sameValue(_ a: JSONTree?, _ b: JSONTree?) -> Bool {
        switch (a, b) {
        case (nil, nil): return true
        case (.object(let x)?, .object(let y)?):
            let xKeys = Set(x.keys.map(UTF16Key.init))
            return xKeys == Set(y.keys.map(UTF16Key.init)) && x.entries.allSatisfy { sameValue($0.value, y[$0.key]) }
        case (.array(let x)?, .array(let y)?):
            return x.count == y.count && zip(x.elements, y.elements).allSatisfy { sameValue($0, $1) }
        case (.object?, _), (_, .object?), (.array?, _), (_, .array?), (nil, _), (_, nil):
            return false
        case (let x?, let y?):
            return samePrimitive(x, y)
        }
    }

    private static func samePrimitive(_ a: JSONTree, _ b: JSONTree) -> Bool {
        if a.isNull || b.isNull { return a.isNull && b.isNull }
        switch (a, b) {
        case (.string(let x), .string(let y)): return Kotlin.same(x, y)
        case (.string, _), (_, .string): return false
        default:
            // Kotlin's `doubleOrNull` of each literal, else their text.
            let x = ModelJSON.content(a).flatMap(Kotlin.toDoubleOrNull)
            let y = ModelJSON.content(b).flatMap(Kotlin.toDoubleOrNull)
            if let x, let y { return x == y }
            return ModelJSON.content(a) == ModelJSON.content(b)
        }
    }

    /// Two versions that differ only in their masks: no conflict, since a mask is worked out, not planned.
    public static func sameApartFromMasks(_ a: JSONObject, _ b: JSONObject) -> Bool {
        sameValue(.object(withoutMasks(a)), .object(withoutMasks(b)))
    }

    private static func withoutMasks(_ data: JSONObject) -> JSONObject {
        guard let threats = data["threats"]?.arrayValue else { return data }
        return data.setting("threats", .array(threats.elements.map { $0.objectValue.map { .object($0.removing("mask")) } ?? $0 }))
    }

    /// Whether two versions of one threat are the same apart from their masks.
    private static func samePlan(_ a: StoredThreat, _ b: StoredThreat) -> Bool {
        a.entry == b.entry && JSONTree.object(a.extras) == .object(b.extras)
    }

    /// Someone else's change (`theirs`) laid over this device's (`mine`), both made from `base`, threat by threat. A threat only they
    /// changed takes their version, one only this device changed keeps it, and one changed on both keeps this device's. One edited
    /// there and removed here stays, edited: no work is lost to a tap on another device; and a mask, being worked out rather than
    /// planned, is never a reason to keep an older threat.
    public static func merge(base: [StoredThreat], mine: [StoredThreat], theirs: [StoredThreat]) -> [StoredThreat] {
        func byId(_ list: [StoredThreat]) -> [UTF16Key: StoredThreat] {
            var out: [UTF16Key: StoredThreat] = [:]
            for t in list { out[UTF16Key(t.entry.id)] = t }
            return out
        }
        let before = byId(base)
        let here = byId(mine)
        let there = byId(theirs)
        var out: [StoredThreat] = []
        for t in theirs {
            let id = UTF16Key(t.entry.id)
            let b = before[id]
            let m = here[id]
            if b == nil {
                out.append(m ?? t)
            } else if m == nil {
                if !samePlan(t, b!) { out.append(t) }
            } else if !samePlan(m!, b!) {
                out.append(m!)
            } else if !samePlan(t, b!) {
                out.append(t)
            } else {
                out.append(m!.mask.map(JSONTree.object) != b!.mask.map(JSONTree.object) ? m! : t)
            }
        }
        for m in mine {
            let id = UTF16Key(m.entry.id)
            if there[id] != nil { continue }
            if let b = before[id], samePlan(m, b) { continue }
            out.append(m)
        }
        return out
    }

    /// A file's name as the set it becomes is called.
    public static func nameFromFile(_ fileName: String) -> String {
        let bare = endsWithThs(fileName) ? Kotlin.substring(fileName, 0, fileName.utf16.count - 4) : fileName
        let name = cut(Kotlin.uppercase(Js.trim(bare)))
        return name.isEmpty ? firstSetName : name
    }

    /// `endsWith(".ths", ignoreCase = true)`: Kotlin matches a character to another when their capitals or their small letters
    /// agree, so the long s (`ſ`, whose capital is S) is an `s` there.
    private static func endsWithThs(_ text: String) -> Bool {
        let u = Array(text.utf16)
        guard u.count >= 4 else { return false }
        let tail = u.suffix(4).map { $0 }
        return tail[0] == 0x2E && (tail[1] | 0x20) == 0x74 && (tail[2] | 0x20) == 0x68
            && ((tail[3] | 0x20) == 0x73 || tail[3] == 0x017F)
    }

    /// How many threats a saved document holds, for a list that has no need to read them.
    public static func countIn(_ data: JSONObject) -> Int { data["threats"]?.arrayValue?.count ?? 0 }

    /// A name as it is kept: trimmed, in capitals, and no longer than a name may be, counted in characters so no emoji is cut in two.
    public static func cleanName(_ name: String) -> String { cut(Kotlin.uppercase(Js.trim(name))) }

    private static func cut(_ text: String) -> String { TypedNumber.cut(text, maxName) }
}
