import CoreJS

/// A grid sent to someone (`docs/GRID_SHARING.md` §1): the same few lines whatever carries them (the system's share sheet, a QR code,
/// a copy), and a link that opens EZ/PZ at the grid.
///
///     LZ HAWK
///     16S GC 28864 55349
///     34.54568° N, 84.12346° W
///     https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK
///
/// The web's `utils/gridShare.js` function by function (by way of Android's GridShare), held to `contracts/fixtures/coords/share.json`:
/// JavaScript's whitespace, its `encodeURIComponent`, and `URLSearchParams`' reading of a fragment are ported here, since
/// Foundation's are not the same.
///
/// A Swift String cannot hold a lone surrogate, so the web's dropping of one (Android's `withoutLoneSurrogates`) has nothing to do
/// here: text that came with one has had it read as U+FFFD before it arrives.
public enum GridShare {
    public static let linkBase = "https://ezpztac.app/"
    public static let labelMax = 60

    // Control characters and line breaks: a label is one line of the person's own words. [\u0000-\u001f\u007f-\u009f  ]
    private static func isControl(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x00...0x1F, 0x7F...0x9F, 0x2028, 0x2029: true
        default: false
        }
    }

    // What draws nothing but can turn or hide what is around it (bidirectional marks and overrides, the zero-width space, the word
    // joiner), so a label cannot draw the grid beside it right to left. The joiners an emoji or a script needs (U+200C, U+200D) stay.
    // [؜​‎‏‪-‮⁠-⁤⁦-⁩]
    private static func isInvisible(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x061C, 0x200B, 0x200E, 0x200F, 0x202A...0x202E, 0x2060...0x2064, 0x2066...0x2069: true
        default: false
        }
    }

    private static func string(_ scalars: some Sequence<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    /// A label as it is shared: one line, spaces collapsed, nothing that turns or hides text, at most 60 code points (a surrogate pair
    /// is one, never split; an emoji joined from several may lose its tail at the cut), or "" for none.
    public static func cleanLabel(_ label: String?) -> String {
        var collapsed: [Unicode.Scalar] = []
        for s in (label ?? "").unicodeScalars where !isInvisible(s) {
            // A control is a space, and every run of JavaScript's spaces one space.
            let spaced = isControl(s) ? " " : s
            if Js.isSpace(spaced) {
                if collapsed.last != " " { collapsed.append(" ") }
            } else {
                collapsed.append(spaced)
            }
        }
        let text = Js.trim(string(collapsed))
        return Js.trim(string(text.unicodeScalars.prefix(labelMax)))
    }

    /// The link that opens EZ/PZ at `mgrs` (spaces taken out), with its label when there is one.
    public static func gridLink(_ mgrs: String, label: String? = nil) -> String {
        let grid = string(mgrs.unicodeScalars.filter { !Js.isSpace($0) }).uppercased()
        let name = cleanLabel(label)
        return "\(linkBase)#grid=\(encodeUriComponent(grid))" + (name.isEmpty ? "" : "&name=\(encodeUriComponent(name))")
    }

    /// The lines a shared grid is, joined by line breaks: the label (when there is one), the grid, the lat/long, the link.
    public static func shareText(_ mgrs: String, _ lat: Double, _ lon: Double, label: String? = nil) -> String {
        let name = cleanLabel(label)
        return [name, mgrs, PlaceSearch.formatLatLong(lat, lon), gridLink(mgrs, label: name)].filter { !$0.isEmpty }.joined(separator: "\n")
    }

    /// What a link asks for: its grid, read as the search reads it, and its label.
    public struct Link: Hashable, Sendable {
        public let choice: PlaceChoice
        public let name: String

        public init(choice: PlaceChoice, name: String) {
            self.choice = choice
            self.name = name
        }
    }

    /// What a link's fragment (`#grid=…&name=…`) asks for, or nil when it has no grid or its grid does not read as one place. A link
    /// names a grid, never a lat/long to guess a side for.
    public static func readGridLink(_ hash: String?) -> Link? {
        // By scalars throughout, as JavaScript reads text by its units: a String's Characters would join a "#", "&" or "=" to a
        // combining mark after it, and then not find it.
        var fragment = Array((hash ?? "").unicodeScalars)
        if fragment.first == "#" { fragment.removeFirst() }
        if fragment.isEmpty { return nil }
        let params = formParams(fragment)
        // Names compared by their UTF-16 units, as URLSearchParams.get compares them.
        func named(_ name: String) -> String? { params.first { $0.name.utf16.elementsEqual(name.utf16) }?.value }
        guard let grid = named("grid"), !grid.isEmpty, grid.utf16.count <= 40 else { return nil }
        let choices = PlaceSearch.read(grid).choices
        guard choices.count == 1, let choice = choices.first, choice.isGrid else { return nil }
        return Link(choice: choice, name: cleanLabel(named("name")))
    }

    // MARK: JavaScript, as the web runs it

    private static let unreserved = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()".utf8)
    private static let hexDigits = Array("0123456789ABCDEF")

    /// JavaScript's `encodeURIComponent`, for text with no lone surrogate (as a String always is): its unreserved set kept, everything
    /// else as UTF-8 %XX.
    static func encodeUriComponent(_ text: String) -> String {
        var out = ""
        for byte in text.utf8 {
            if unreserved.contains(byte) {
                out.append(Character(Unicode.Scalar(byte)))
            } else {
                out.append("%")
                out.append(hexDigits[Int(byte >> 4)])
                out.append(hexDigits[Int(byte & 0xF)])
            }
        }
        return out
    }

    /// `new URLSearchParams(text)`: pieces split on "&", an empty one skipped, each name and value form-decoded (WHATWG).
    private static func formParams(_ text: [Unicode.Scalar]) -> [(name: String, value: String)] {
        text.split(separator: "&", omittingEmptySubsequences: true).map { piece in
            guard let i = piece.firstIndex(of: "=") else { return (formDecode(piece), "") }
            return (formDecode(piece[..<i]), formDecode(piece[(i + 1)...]))
        }
    }

    // "+" is a space; "%" and two hex digits a byte, any other "%" itself; the bytes then read as UTF-8 as WHATWG reads it
    // (utf8Decode).
    private static func formDecode(_ text: ArraySlice<Unicode.Scalar>) -> String {
        let bytes = Array(string(text).utf8.map { $0 == UInt8(ascii: "+") ? UInt8(ascii: " ") : $0 })
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count)
        var i = 0
        while i < bytes.count {
            let b = bytes[i]
            if b == UInt8(ascii: "%"), i + 2 < bytes.count, let high = hex(bytes[i + 1]), let low = hex(bytes[i + 2]) {
                out.append(high * 16 + low)
                i += 3
            } else {
                out.append(b)
                i += 1
            }
        }
        return utf8Decode(out)
    }

    /// The Encoding Standard's UTF-8 decoder: one U+FFFD for each maximal part of a sequence that cannot be completed. Foundation's
    /// decoder is not held to it, so it is spelled out as the standard writes it (an encoded surrogate, `ED A0 80`, is three U+FFFD).
    static func utf8Decode(_ bytes: [UInt8]) -> String {
        var out = String.UnicodeScalarView()
        var needed = 0
        var seen = 0
        var codePoint: UInt32 = 0
        var lower: UInt8 = 0x80
        var upper: UInt8 = 0xBF
        var i = 0
        while i < bytes.count {
            let b = bytes[i]
            if needed == 0 {
                switch b {
                case 0x00...0x7F:
                    out.append(Unicode.Scalar(b))
                case 0xC2...0xDF:
                    needed = 1
                    codePoint = UInt32(b & 0x1F)
                case 0xE0...0xEF:
                    if b == 0xE0 { lower = 0xA0 }
                    if b == 0xED { upper = 0x9F }
                    needed = 2
                    codePoint = UInt32(b & 0xF)
                case 0xF0...0xF4:
                    if b == 0xF0 { lower = 0x90 }
                    if b == 0xF4 { upper = 0x8F }
                    needed = 3
                    codePoint = UInt32(b & 0x7)
                default:
                    out.append("\u{FFFD}")
                }
                i += 1
                continue
            }
            if !(lower...upper).contains(b) {
                // This byte starts again: the sequence it broke is one U+FFFD.
                needed = 0
                seen = 0
                codePoint = 0
                lower = 0x80
                upper = 0xBF
                out.append("\u{FFFD}")
                continue
            }
            lower = 0x80
            upper = 0xBF
            codePoint = (codePoint << 6) | UInt32(b & 0x3F)
            seen += 1
            i += 1
            if seen == needed {
                // The bounds above keep a decoded code point a scalar: no surrogate, nothing past U+10FFFF.
                out.append(Unicode.Scalar(codePoint)!)
                needed = 0
                seen = 0
                codePoint = 0
            }
        }
        if needed != 0 { out.append("\u{FFFD}") }
        return String(out)
    }

    private static func hex(_ b: UInt8) -> UInt8? {
        switch b {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): b - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): b - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): b - UInt8(ascii: "A") + 10
        default: nil
        }
    }
}
