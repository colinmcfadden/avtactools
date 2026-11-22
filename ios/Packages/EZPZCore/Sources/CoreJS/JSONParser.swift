// Reading JSON text into a JSONTree, as `JSON.parse` reads it: the same grammar (no comments, no trailing commas, no byte order
// mark, white space only the four JSON has), a key given twice keeping its first place and its last value.
//
// Text from a server or a pack member is hostile. The parser keeps its own stack, so no nesting can overflow the thread's (five
// thousand `[`, ten kilobytes, ended Kotlin's recursive parser: AGENTS.md §14), and nesting is bounded besides, since the writer,
// `==` and the ports' walkers recurse, and so does freeing a tree: Swift releases a nested object inside its parent's release, so a
// tree tens of thousands deep ends the app as it goes away, where Kotlin's garbage collector never recursed.
//
// One difference from JavaScript, which Swift's String cannot avoid: a lone surrogate (`"\ud800"`), which a JavaScript string can
// hold, is read as U+FFFD. The web writes one only by its known bug of cutting text through an emoji (AGENTS.md §15).

public struct JSONError: Error, Equatable, CustomStringConvertible {
    public enum Reason: Equatable, Sendable {
        case unexpectedEnd
        case unexpected(String)
        case badNumber
        case badEscape
        case controlCharacter
        case invalidUTF8
        case tooDeep(Int)
        case trailingText
    }

    public let reason: Reason
    /// The byte offset in the UTF-8 text where reading stopped.
    public let offset: Int

    public var description: String {
        let what: String
        switch reason {
        case .unexpectedEnd: what = "the text ended early"
        case .unexpected(let c): what = "unexpected \(c)"
        case .badNumber: what = "not a number"
        case .badEscape: what = "a bad escape in a string"
        case .controlCharacter: what = "a control character in a string"
        case .invalidUTF8: what = "text that is not UTF-8"
        case .tooDeep(let limit): what = "nested deeper than \(limit)"
        case .trailingText: what = "text after the value"
        }
        return "JSON: \(what) at byte \(offset)"
    }
}

extension JSONTree {
    /// How deep a document may nest when no other limit is given. The deepest fixture nests 15 levels; the writer, `==` and the
    /// ports' walkers recurse, and 64 levels of them fit the 512 KB stack of a secondary thread with room to spare.
    public static let defaultMaxDepth = 64

    /// Reads `text`, or throws `JSONError`.
    public static func parse(_ text: String, maxDepth: Int = defaultMaxDepth) throws(JSONError) -> JSONTree {
        try parse(utf8: Array(text.utf8), maxDepth: maxDepth)
    }

    /// Reads UTF-8 bytes, or throws `JSONError` (bytes that are not UTF-8 included).
    public static func parse(utf8 bytes: some Collection<UInt8>, maxDepth: Int = defaultMaxDepth) throws(JSONError) -> JSONTree {
        var parser = Parser(bytes: Array(bytes), maxDepth: maxDepth)
        parser.skipSpace()
        let value = try parser.value()
        parser.skipSpace()
        if parser.at < parser.bytes.count { throw JSONError(reason: .trailingText, offset: parser.at) }
        return value
    }
}

private struct Parser {
    let bytes: [UInt8]
    let maxDepth: Int
    var at = 0

    init(bytes: [UInt8], maxDepth: Int) {
        self.bytes = bytes
        self.maxDepth = maxDepth
    }

    func fail(_ reason: JSONError.Reason) -> JSONError { JSONError(reason: reason, offset: at) }

    mutating func skipSpace() {
        while at < bytes.count, bytes[at] == 0x20 || bytes[at] == 0x09 || bytes[at] == 0x0A || bytes[at] == 0x0D { at += 1 }
    }

    func peek() throws(JSONError) -> UInt8 {
        guard at < bytes.count else { throw fail(.unexpectedEnd) }
        return bytes[at]
    }

    func unexpected() -> JSONError {
        guard at < bytes.count else { return fail(.unexpectedEnd) }
        let b = bytes[at]
        return fail(.unexpected(b >= 0x20 && b < 0x7F ? "'\(Character(Unicode.Scalar(b)))'" : "byte \(b)"))
    }

    /// One container being read: a list's elements so far, or an object's entries and the key of the value being read. A struct
    /// mutated in place on the stack (`stack[last].elements.append`): an enum's payload taken out, appended to and put back is a
    /// second reference to the same buffer, which copies it at every element, and a list of a few hundred thousand then takes minutes.
    struct Frame {
        let isObject: Bool
        var elements: [JSONTree] = []
        var entries: [(String, JSONTree)] = []
        var key = ""
    }

    /// Reads one value with a stack of its own rather than the thread's: how deep a document nests costs memory here, never stack,
    /// so no text can end the app however it nests (and the bound refuses it long before memory matters).
    mutating func value() throws(JSONError) -> JSONTree {
        var stack: [Frame] = []
        read: while true {
            skipSpace()
            var current: JSONTree
            switch try peek() {
            case UInt8(ascii: "{"):
                if stack.count + 1 > maxDepth { throw fail(.tooDeep(maxDepth)) }
                at += 1
                skipSpace()
                if try peek() == UInt8(ascii: "}") {
                    at += 1
                    current = .object(JSONObject())
                } else {
                    stack.append(Frame(isObject: true, key: try key()))
                    continue read
                }
            case UInt8(ascii: "["):
                if stack.count + 1 > maxDepth { throw fail(.tooDeep(maxDepth)) }
                at += 1
                skipSpace()
                if try peek() == UInt8(ascii: "]") {
                    at += 1
                    current = .array(JSONArray())
                } else {
                    stack.append(Frame(isObject: false))
                    continue read
                }
            case UInt8(ascii: "\""): current = .string(try string())
            case UInt8(ascii: "t"):
                try word("true")
                current = .bool(true)
            case UInt8(ascii: "f"):
                try word("false")
                current = .bool(false)
            case UInt8(ascii: "n"):
                try word("null")
                current = .null
            case UInt8(ascii: "-"), UInt8(ascii: "0")...UInt8(ascii: "9"): current = .number(try number())
            default: throw unexpected()
            }
            // A value is complete: put it in its container, and close every container it completes.
            while !stack.isEmpty {
                let last = stack.count - 1
                skipSpace()
                if stack[last].isObject {
                    stack[last].entries.append((stack[last].key, current))
                    switch try peek() {
                    case UInt8(ascii: ","):
                        at += 1
                        skipSpace()
                        stack[last].key = try key()
                        continue read
                    case UInt8(ascii: "}"):
                        at += 1
                        current = .object(JSONObject(stack.removeLast().entries))
                    default: throw unexpected()
                    }
                } else {
                    stack[last].elements.append(current)
                    switch try peek() {
                    case UInt8(ascii: ","):
                        at += 1
                        continue read
                    case UInt8(ascii: "]"):
                        at += 1
                        current = .array(JSONArray(stack.removeLast().elements))
                    default: throw unexpected()
                    }
                }
            }
            return current
        }
    }

    /// An object's key and the colon after it.
    mutating func key() throws(JSONError) -> String {
        guard try peek() == UInt8(ascii: "\"") else { throw unexpected() }
        let key = try string()
        skipSpace()
        guard try peek() == UInt8(ascii: ":") else { throw unexpected() }
        at += 1
        return key
    }

    mutating func word(_ w: String) throws(JSONError) {
        for b in w.utf8 {
            guard at < bytes.count else { throw fail(.unexpectedEnd) }
            guard bytes[at] == b else { throw unexpected() }
            at += 1
        }
    }

    // -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
    mutating func number() throws(JSONError) -> JSONNumber {
        let start = at
        func isDigit(_ i: Int) -> Bool { i < bytes.count && bytes[i] >= 0x30 && bytes[i] <= 0x39 }
        if bytes[at] == UInt8(ascii: "-") { at += 1 }
        guard isDigit(at) else { throw fail(.badNumber) }
        if bytes[at] == UInt8(ascii: "0") {
            at += 1
        } else {
            while isDigit(at) { at += 1 }
        }
        if at < bytes.count, bytes[at] == UInt8(ascii: ".") {
            at += 1
            guard isDigit(at) else { throw fail(.badNumber) }
            while isDigit(at) { at += 1 }
        }
        if at < bytes.count, bytes[at] == UInt8(ascii: "e") || bytes[at] == UInt8(ascii: "E") {
            at += 1
            if at < bytes.count, bytes[at] == UInt8(ascii: "+") || bytes[at] == UInt8(ascii: "-") { at += 1 }
            guard isDigit(at) else { throw fail(.badNumber) }
            while isDigit(at) { at += 1 }
        }
        return JSONNumber(literal: String(decoding: bytes[start..<at], as: UTF8.self))
    }

    mutating func string() throws(JSONError) -> String {
        at += 1  // the opening quote
        var units: [UInt16] = []
        var runStart = at
        func flushRun(_ end: Int) throws(JSONError) {
            guard runStart < end else { return }
            // A run of raw bytes: strict UTF-8, as text that is not UTF-8 is not JSON text.
            var iterator = bytes[runStart..<end].makeIterator()
            var decoder = UTF8()
            loop: while true {
                switch decoder.decode(&iterator) {
                case .scalarValue(let s): units.append(contentsOf: UTF16.encode(s)!)
                case .emptyInput: break loop
                case .error: throw JSONError(reason: .invalidUTF8, offset: runStart)
                }
            }
        }
        while true {
            guard at < bytes.count else { throw fail(.unexpectedEnd) }
            let b = bytes[at]
            if b == UInt8(ascii: "\"") {
                try flushRun(at)
                at += 1
                return String(decoding: units, as: UTF16.self)  // a lone surrogate becomes U+FFFD here
            }
            if b < 0x20 { throw fail(.controlCharacter) }
            if b != UInt8(ascii: "\\") {
                at += 1
                continue
            }
            try flushRun(at)
            at += 1
            guard at < bytes.count else { throw fail(.unexpectedEnd) }
            let e = bytes[at]
            at += 1
            switch e {
            case UInt8(ascii: "\""): units.append(0x22)
            case UInt8(ascii: "\\"): units.append(0x5C)
            case UInt8(ascii: "/"): units.append(0x2F)
            case UInt8(ascii: "b"): units.append(0x08)
            case UInt8(ascii: "f"): units.append(0x0C)
            case UInt8(ascii: "n"): units.append(0x0A)
            case UInt8(ascii: "r"): units.append(0x0D)
            case UInt8(ascii: "t"): units.append(0x09)
            case UInt8(ascii: "u"):
                guard at + 4 <= bytes.count else { throw fail(.unexpectedEnd) }
                var unit: UInt16 = 0
                for _ in 0..<4 {
                    guard let d = BigUInt.digit(Unicode.Scalar(bytes[at])), d < 16 else { throw fail(.badEscape) }
                    unit = unit << 4 | UInt16(d)
                    at += 1
                }
                units.append(unit)
            default:
                at -= 1
                throw fail(.badEscape)
            }
            runStart = at
        }
    }
}
