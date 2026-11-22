import CoreJS

/// What a doghouse says, how a typed value is written into it, and how the two standard doghouses give a diagram's flight data its
/// landing and takeoff headings: the web's `doghouseFields.js` and `useDoghouses.js`, held to
/// `contracts/fixtures/workspace/doghouses.json`.
///
/// A doghouse is the label box beside the landing zone: a heading, a time (`MM+SS`), a distance and an airspeed, kept as the web
/// writes them (`"270°"`, `"03+20"`, `"3.13km"`, `"60 kts"`), because the document is the one the web opens too.
public enum Doghouses {
    private static func text(_ value: JSONTree?) -> String? { JsValue.string(value) }

    /// `String(value)`, with an absent value as `"undefined"` and null as `"null"`, which is what `parseInt` and `parseFloat` are
    /// given: a list by its elements joined by commas (`[306]` is `"306"`), an object as `"[object Object]"`.
    private static func jsString(_ value: JSONTree?) -> String { Js.text(value) }

    private static func orZero(_ value: Double) -> Double { value.isNaN || value == 0 ? 0 : value }

    /// How the box is turned: the leading integer of the stored heading (`"270°"` is 270), or 0. Not wrapped, so `"-90°"` is -90.
    public static func rotation(_ doghouse: JSONObject) -> Double { orZero(JsNumber.parseInt(jsString(doghouse["heading"]))) }

    /// The landing heading a flight data's `landing_hdg` gives (`value`, as stored: the landing doghouse writes it), read as the box
    /// reads its text, by its leading integer (`"270°"` is 270, `"-90°"` is 270), as 0..359: the web's `landingHeadingOf`
    /// (`feature/terrain/slopeCall.js`). Nil when there is none: unlike the box, which draws an unreadable heading as north, a
    /// heading that cannot be read is not taken as 000.
    public static func landingHeadingOf(_ value: JSONTree?) -> Int? {
        let degrees = JsNumber.parseInt(jsString(value))
        guard degrees.isFinite else { return nil }
        return Int((degrees.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360))
    }

    /// The landing heading of `flightData` (`landingHeadingOf` its `landing_hdg`), the one the slope tile judges.
    public static func landingHeading(_ flightData: JSONObject) -> Int? { landingHeadingOf(flightData["landing_hdg"]) }

    /// What the label and the four rows of a doghouse show. `id` is nil when the doghouse has no label.
    public struct Display: Hashable, Sendable {
        public var id: String?
        public var heading: String
        public var minutes: String
        public var seconds: String
        public var distance: Double
        public var airspeed: Double

        public init(id: String?, heading: String, minutes: String, seconds: String, distance: Double, airspeed: Double) {
            self.id = id
            self.heading = heading
            self.minutes = minutes
            self.seconds = seconds
            self.distance = distance
            self.airspeed = airspeed
        }

        /// The distance as the box prints it: 3.13 is "3.13", 5.0 is "5".
        public var distanceText: String { JsNumber.toText(distance) }

        /// The airspeed as the box prints it.
        public var airspeedText: String { JsNumber.toText(airspeed) }
    }

    /// What `doghouse` shows with its box turned by `rotation` degrees (the box is turned by `rotation` of the same doghouse).
    public static func display(_ doghouse: JSONObject, _ rotation: Double) -> Display {
        let timeValue = doghouse["time"]
        let time = Kotlin.split(Js.truthy(timeValue) ? jsString(timeValue) : "00+00", on: 0x2B)
        let speedValue = doghouse["airspeed"]
        let airspeed = Js.truthy(speedValue) ? Kotlin.split(jsString(speedValue), on: 0x20)[0] : "90"
        let speed = JsNumber.parseInt(airspeed)
        func part(_ i: Int) -> String { i < time.count && !time[i].isEmpty ? time[i] : "00" }
        return Display(
            id: text(doghouse["id_val"]),
            heading: Kotlin.padStart(integerText((rotation + 0.5).rounded(.down)), 3, "0"),
            minutes: part(0),
            seconds: part(1),
            distance: orZero(JsNumber.parseFloat(jsString(doghouse["dist"]))),
            airspeed: speed.isNaN || speed == 0 ? 90 : speed
        )
    }

    /// A whole number as JavaScript prints it (`-0` is "0", NaN is "NaN"): `JsNumber.toText`.
    private static func integerText(_ value: Double) -> String { JsNumber.toText(value) }

    /// A typed heading as degrees: its leading integer, wrapped **once** into 0..359 (one below -360 stays negative, as
    /// JavaScript's `%` does).
    public static func headingDegrees(_ typed: String) -> Double {
        (orZero(JsNumber.parseInt(typed)) + 360).truncatingRemainder(dividingBy: 360)
    }

    /// The stored form of a heading in degrees: three digits and a degree sign.
    public static func headingText(_ degrees: Double) -> String { Kotlin.padStart(integerText(degrees), 3, "0") + "°" }

    /// What writing `value` into the field `type` stores on the doghouse: `"id"` the label, `"dist"` kilometres, `"airspeed"` knots,
    /// or one of the `"time-m"` / `"time-s"` halves, either of which rewrites the whole time from `minutes` and `seconds` (what both
    /// halves say now). The heading is written by `headingText`; any other `type` stores nothing.
    public static func fieldUpdates(_ type: String, _ value: String, minutes: String = "", seconds: String = "") -> JSONObject {
        switch type {
        case "id": JSONObject([("id_val", .string(value))])
        case "dist": JSONObject([("dist", .string(value + "km"))])
        case "airspeed": JSONObject([("airspeed", .string(value + " kts"))])
        case _ where type.utf16.starts(with: "time".utf16): JSONObject([("time", .string("\(minutes)+\(seconds)"))])
        default: JSONObject()
        }
    }

    // MARK: What a person types
    //
    // The web accepts anything typed into a doghouse's box and stores it (`3.5` becomes "3.5km", whatever it was). A phone asks for
    // what the field is for, so these refuse what cannot be one; what they accept is stored exactly as the web would store it.

    /// The longest label that fits the triangle.
    public static let maxLabel = 12

    /// The label as typed (`[SP1]`, `IP 2`), trimmed; nil if it is blank or too long for the triangle.
    public static func labelPatch(_ typed: String) -> JSONObject? {
        let label = Kotlin.trim(typed)
        guard !label.isEmpty, label.utf16.count <= maxLabel else { return nil }
        return fieldUpdates("id", label)
    }

    /// A time typed as minutes and seconds, `3+20`, `03+20` or `3:20`, stored as `03+20`; nil for anything else, and for 60 seconds
    /// or more. (`(\d{1,3})\s*[+:]\s*(\d{1,2})` matched whole, Java's ASCII `\d` and `\s`.)
    public static func timePatch(_ typed: String) -> JSONObject? {
        let u = Array(Kotlin.trim(typed).utf16)
        func isDigit(_ i: Int) -> Bool { i < u.count && u[i] >= 0x30 && u[i] <= 0x39 }
        func isSpace(_ i: Int) -> Bool { i < u.count && (u[i] == 0x20 || (u[i] >= 0x09 && u[i] <= 0x0D)) }
        var i = 0
        while isDigit(i) { i += 1 }
        let minutesEnd = i
        guard (1...3).contains(minutesEnd) else { return nil }
        while isSpace(i) { i += 1 }
        guard i < u.count, u[i] == 0x2B || u[i] == 0x3A else { return nil }
        i += 1
        while isSpace(i) { i += 1 }
        let secondsStart = i
        while isDigit(i) { i += 1 }
        guard (1...2).contains(i - secondsStart), i == u.count else { return nil }
        let minutes = String(decoding: u[0..<minutesEnd], as: UTF16.self)
        let seconds = Int(String(decoding: u[secondsStart..<i], as: UTF16.self))!
        guard seconds <= 59 else { return nil }
        return fieldUpdates("time-m", "", minutes: Kotlin.padStart(minutes, 2, "0"), seconds: Kotlin.padStart(String(seconds), 2, "0"))
    }

    /// A distance in kilometres, `3`, `3.13`, stored as `3.13km`; nil for anything else (`\d{1,4}(\.\d{1,3})?`).
    public static func distancePatch(_ typed: String) -> JSONObject? {
        let text = Kotlin.trim(typed)
        let parts = Kotlin.split(text, on: 0x2E)
        let whole = parts[0].utf16
        guard Kotlin.asciiDigits(whole, 1...4) else { return nil }
        if parts.count == 2 { guard Kotlin.asciiDigits(parts[1].utf16, 1...3) else { return nil } }
        guard parts.count <= 2 else { return nil }
        return fieldUpdates("dist", text)
    }

    /// An airspeed in knots, `60`, stored as `60 kts`; nil for anything else (`\d{1,3}`).
    public static func airspeedPatch(_ typed: String) -> JSONObject? {
        let text = Kotlin.trim(typed)
        guard Kotlin.asciiDigits(text.utf16, 1...3) else { return nil }
        return fieldUpdates("airspeed", text)
    }

    private static func isString(_ value: JSONTree?, _ expected: String) -> Bool {
        if case .string(let s)? = value { Kotlin.same(s, expected) } else { false }
    }

    /// The landing doghouse: new ones have `role` "landing"; the older ids `dh2` and label `[RP1]` keep saved diagrams working.
    public static func isLanding(_ doghouse: JSONObject) -> Bool {
        isString(doghouse["role"], "landing") || isString(doghouse["id"], "dh2") || isString(doghouse["id_val"], "[RP1]")
    }

    /// The takeoff doghouse: `role` "takeoff", or the older id `dh1` or label `[SP1]`.
    public static func isTakeoff(_ doghouse: JSONObject) -> Bool {
        isString(doghouse["role"], "takeoff") || isString(doghouse["id"], "dh1") || isString(doghouse["id_val"], "[SP1]")
    }

    /// `flightData` with the landing and takeoff headings the doghouses give (`landing_hdg`, `takeoff_hdg`): the first doghouse that
    /// matches wins, a heading it does not have takes the key away, a kind with no doghouse keeps what the flight data had, and with
    /// neither doghouse the flight data is returned as it was. What is not a doghouse is skipped.
    public static func flightHeadings(_ doghouses: [JSONTree], _ flightData: JSONObject) -> JSONObject {
        let list = doghouses.compactMap(\.objectValue)
        let landing = list.first(where: isLanding)
        let takeoff = list.first(where: isTakeoff)
        if landing == nil && takeoff == nil { return flightData }
        var out = flightData
        for (key, doghouse) in [("landing_hdg", landing), ("takeoff_hdg", takeoff)] {
            guard let doghouse else { continue }
            if let heading = doghouse["heading"] { out = out.setting(key, heading) } else { out = out.removing(key) }
        }
        return out
    }

    /// `diagram` with its flight data brought in line with its doghouses, when they differ from `before`'s (the web does it
    /// whenever they change).
    public static func settle(_ before: Diagram?, _ diagram: Diagram) -> Diagram {
        if let before, before.graphics.doghouses == diagram.graphics.doghouses { return diagram }
        let flight = flightHeadings(diagram.graphics.doghouses, diagram.flightData)
        guard JSONTree.object(flight) != .object(diagram.flightData) else { return diagram }
        var copy = diagram
        copy.flightData = flight
        return copy
    }
}
