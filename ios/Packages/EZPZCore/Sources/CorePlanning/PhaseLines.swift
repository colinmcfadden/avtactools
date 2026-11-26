import CoreJS
import CoreModel
import Foundation

/// A phase line (the owner's, 2026-11-14): a control measure drawn across the map as a plain solid line with `PL <NAME>` at each end, as
/// the doctrinal graphic is. Kept with a set of routes beside its routes (`phaseLines` of the set's document) as `{id, name, points}`.
/// `name` is what follows PL, in capitals. Any member of a pack can type one, so it is drawn as text, never markup. The web's
/// `feature/phaseLines/phaseLines.js`, held to contracts/fixtures/planning/controlMeasures.json.
public enum PhaseLines {
    public static let nameMax = 24

    static let phonetic = [
        "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE",
        "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU",
    ]

    /// A name as a phase line keeps it: in capitals, its spaces single, without a PL typed in front of it (the label says PL itself),
    /// and at most `nameMax` characters, counted as code points (`[...name]`) so none is cut in half. Nothing left is no name.
    ///
    /// `toUpperCase` is the Unicode default full mapping (`ß` is `SS`), as `uppercased()` is; neither depends on the device's language.
    public static func cleanName(_ text: JSONTree?) -> String {
        // `String(text ?? "")`
        let raw = JsValue.present(text).map { Js.text($0) } ?? ""
        let upper = Js.trim(raw).uppercased()
        // `.replace(/\s+/g, " ")`, JavaScript's white space.
        var single = String.UnicodeScalarView()
        var inSpace = false
        for scalar in upper.unicodeScalars {
            if Js.isSpace(scalar) {
                if !inSpace { single.append(" ") }
                inSpace = true
            } else {
                single.append(scalar)
                inSpace = false
            }
        }
        var scalars = Array(single)
        // `.replace(/^PL(?: |$)/, "")`
        if scalars.starts(with: ["P", "L"]), scalars.count == 2 || scalars[2] == " " {
            scalars.removeFirst(min(3, scalars.count))
        }
        return Js.trim(String(String.UnicodeScalarView(scalars.prefix(nameMax))))
    }

    /// What is written at each end of a phase line.
    public static func label(_ line: JSONTree?) -> String {
        guard case .string(let s)? = Js.prop(line, "name") else { return "PL" }
        let name = Js.trim(s)
        return name.isEmpty ? "PL" : "PL \(name)"
    }

    /// The name a new phase line is offered: the first word of the phonetic alphabet none of `lines` has, and past ZULU a number.
    public static func nextName(_ lines: [JSONTree]?) -> String {
        let taken = Set((lines ?? []).map { UTF16Key(cleanName(Js.prop($0, "name"))) })
        if let word = phonetic.first(where: { !taken.contains(UTF16Key($0)) }) { return word }
        var n = phonetic.count + 1
        while taken.contains(UTF16Key(String(n))) { n += 1 }
        return String(n)
    }

    /// A new phase line's document, `{id, name, points}`.
    public static func create(points: [LatLon], id: String, name: JSONTree?) -> JSONObject {
        JSONObject([("id", .string(id)), ("name", .string(cleanName(name))), ("points", SetLines.plainPoints(points))])
    }

    /// The phase line under another name; a name with nothing in it changes nothing (and keeps the same object).
    public static func rename(_ line: JSONObject, to name: JSONTree?) -> JSONObject {
        let next = cleanName(name)
        return next.isEmpty ? line : line.setting("name", .string(next))
    }

    /// One of a phase line's two ends, and the unit vector that leads on out of the line there (screen y runs downward).
    public struct End: Equatable, Sendable {
        public let x: Double
        public let y: Double
        public let ux: Double
        public let uy: Double
    }

    static func outward(from end: ScreenPoint, _ inward: some Sequence<ScreenPoint>) -> (ux: Double, uy: Double)? {
        for point in inward {
            let (dx, dy) = (end.x - point.x, end.y - point.y)
            let length = hypot(dx, dy)
            if length > 0 { return (dx / length, dy / length) }
        }
        return nil
    }

    /// Where a phase line's two labels go, from its points in screen points: its first and last point, each with the direction out of
    /// the line there. A line with no length has none.
    public static func ends(_ pixels: [ScreenPoint]) -> [End] {
        guard pixels.count >= 2, let first = pixels.first, let last = pixels.last,
            let a = outward(from: first, pixels.dropFirst()), let b = outward(from: last, pixels.dropLast().reversed())
        else { return [] }
        return [End(x: first.x, y: first.y, ux: a.ux, uy: a.uy), End(x: last.x, y: last.y, ux: b.ux, uy: b.uy)]
    }

    /// How far a label's middle is from the end it names, in halves of its own width (`fx`) and height (`fy`): the direction out of the
    /// line stretched until it reaches the label's edge, so the end is on that edge whatever the label's size.
    public static func labelShift(ux: Double, uy: Double) -> (fx: Double, fy: Double) {
        let reach = max(abs(ux), abs(uy))
        return reach > 0 ? (ux / reach, uy / reach) : (0, 0)
    }
}
