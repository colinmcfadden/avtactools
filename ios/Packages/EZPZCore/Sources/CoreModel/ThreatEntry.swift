import CoreJS

/// A threat in the picture a crew is planning against: the threat as it is exported, with the id the app gives it and whether it is
/// shown. The web's `visible` and `id` (`useThreats`); its colour and its fetched mask are not here, because the colours of a
/// threat's rings are the radar type's.
///
/// What is saved to the Library is the web's own flat threat, read and written by `ThreatSets`; this is what the screens and the map
/// use, and what an account that cannot save threats keeps in its sealed file on the device.
public struct ThreatEntry: Hashable, Sendable, ModelJSONCodable {
    public var id: String
    public var threat: Threat
    public var visible: Bool

    public init(id: String, threat: Threat, visible: Bool = true) {
        self.id = id
        self.threat = threat
        self.visible = visible
    }

    public static let serialNames = ["id", "threat", "visible"]

    public init(from r: ModelJSON.ObjectReader) throws(ModelJSONError) {
        let j = r.json
        self.init(
            id: try r.required("id", j.string),
            threat: try r.required("threat", j.object),
            visible: try r.optional("visible", default: true, j.bool)
        )
    }

    public func encode(to w: inout ModelJSON.ObjectWriter) {
        w.put("id", id)
        w.put("threat", model: threat)
        w.put("visible", visible)
    }
}

/// How threats are told apart. The web's `threat-<millis>-<n>`; here nudged past any taken, so two made in one millisecond do not
/// collide.
public enum ThreatIds {
    public static func next(_ taken: some Collection<String>, _ nowMillis: Int64) -> String {
        let used = Set(taken.map(UTF16Key.init))
        var n = 0
        while true {
            let id = "threat-\(nowMillis)-\(n)"
            if !used.contains(UTF16Key(id)) { return id }
            n += 1
        }
    }
}
