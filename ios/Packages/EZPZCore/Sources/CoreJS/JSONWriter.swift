// Writing a JSONTree as text: compact, as `JSON.stringify(value)` writes it, except that a number is written with the literal text
// it came with (kotlinx's JsonElement.toString() does the same, and the pack store relies on it: `2.50` read from a pack is `2.50`
// when it goes back). Keys are in the object's order, which is JavaScript's.

extension JSONTree {
    /// The compact JSON text of this value.
    public var serialized: String {
        var out = ""
        write(into: &out)
        return out
    }

    /// The same text, as UTF-8 bytes.
    public var serializedUTF8: [UInt8] { Array(serialized.utf8) }

    private func write(into out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let n): out += n.isMadeNonFinite ? "null" : n.text  // JSON.stringify writes NaN and the infinities as null
        case .string(let s): JSONTree.quote(s, into: &out)
        case .array(let a):
            out += "["
            for (i, element) in a.elements.enumerated() {
                if i > 0 { out += "," }
                element.write(into: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (i, entry) in o.entries.enumerated() {
                if i > 0 { out += "," }
                JSONTree.quote(entry.key, into: &out)
                out += ":"
                entry.value.write(into: &out)
            }
            out += "}"
        }
    }

    /// `JSON.stringify(text)`: `"` and `\` escaped, the C0 controls as `\b \f \n \r \t` or `\u00xx` in lower case, everything
    /// else (U+2028 and U+2029 among it) as it is.
    static func quote(_ text: String, into out: inout String) {
        out += "\""
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x00..<0x20:
                let hex = String(scalar.value, radix: 16)
                out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }
}
