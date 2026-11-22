import CoreJS

/// JavaScript's `??` over a parsed document, the part of Android's `JsValue` CoreJS does not already have (`Js.truthy`,
/// `JsNumber.of`, `Js.trim`, `Js.text`).
public enum JsValue {
    /// `value ?? undefined`: null and absent are both nothing.
    public static func present(_ value: JSONTree?) -> JSONTree? {
        if case .null? = value { return nil }
        return value
    }

    /// The first of the values that is neither null nor absent (a chain of `??`).
    public static func firstPresent(_ values: JSONTree?...) -> JSONTree? {
        for value in values { if let v = present(value) { return v } }
        return nil
    }

    /// `String(value)` for a primitive (text as it is, a number as JavaScript writes it, true or false); nil for nothing, a list or
    /// an object.
    public static func string(_ value: JSONTree?) -> String? {
        switch value {
        case .string(let s)?: s
        case .number?, .bool?: Js.text(value)
        default: nil
        }
    }
}
