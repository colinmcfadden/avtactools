/// MIL-STD-2525C symbol identification codes (SIDCs), as the web's unit builder makes and reads them
/// (`frontend/src/feature/symbols/sidc.js`, held to `contracts/fixtures/symbols/sidc.json`).
///
/// A 2525C SIDC is 15 characters: `S`, the affiliation (F friend, H hostile, N neutral, U unknown), the dimension (G ground, A air, S
/// sea surface), the status (P present, A anticipated), six characters of function, the echelon, and four more. These are written to
/// be forgiving the way the web's are: a part that is too short is padded and one that is too long is cut, and the whole is cut or
/// padded to 15. Lengths and places are UTF-16 units, as the web's and Kotlin's are.
public enum Sidc {
    /// The parts the unit builder edits, as read from a SIDC.
    public struct Parts: Hashable, Sendable {
        public var affiliation: String
        public var dimension: String
        public var status: String
        public var functionId: String
        public var echelon: String

        public init(affiliation: String, dimension: String, status: String, functionId: String, echelon: String) {
            self.affiliation = affiliation
            self.dimension = dimension
            self.status = status
            self.functionId = functionId
            self.echelon = echelon
        }
    }

    /// A SIDC from its parts (`buildSidc`). Each default is what the web's is; a part is not checked, only fitted.
    public static func build(
        affiliation: String = "F",
        dimension: String = "G",
        status: String = "P",
        functionId: String = "------",
        echelon: String = "-"
    ) -> String {
        let function = Kotlin.take(functionId + "------", 6)
        return Kotlin.padEnd(Kotlin.take("S\(affiliation)\(dimension)\(status)\(function)\(echelon)----", 15), 15, "-")
    }

    /// `sidc` with another affiliation at its second position; a SIDC of fewer than two characters (or none) is returned as it is.
    public static func withAffiliation(_ sidc: String?, _ affiliation: String) -> String? {
        guard let sidc, sidc.utf16.count >= 2 else { return sidc }
        return Kotlin.substring(sidc, 0, 1) + affiliation + Kotlin.substring(sidc, 2, sidc.utf16.count)
    }

    /// The affiliation character of `sidc`, `U` (unknown) if it has none.
    public static func affiliation(_ sidc: String?) -> String {
        guard let sidc, sidc.utf16.count >= 2 else { return "U" }
        return Kotlin.substring(sidc, 1, 2)
    }

    /// The parts of a SIDC, or nil when it is too short (under 11 characters) to have them.
    public static func parse(_ sidc: String?) -> Parts? {
        guard let sidc, sidc.utf16.count >= 11 else { return nil }
        return Parts(
            affiliation: Kotlin.substring(sidc, 1, 2),
            dimension: Kotlin.substring(sidc, 2, 3),
            status: Kotlin.substring(sidc, 3, 4),
            functionId: Kotlin.substring(sidc, 4, 10),
            echelon: Kotlin.substring(sidc, 10, 11)
        )
    }
}
