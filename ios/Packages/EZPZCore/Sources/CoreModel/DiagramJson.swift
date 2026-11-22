import CoreJS

/// A diagram as the JSON the web saves: every field written, nulls included, as `serializeLzDiagram` writes them. The library's
/// records and a mission pack's items are both made from it, so the two cannot drift apart.
public enum DiagramJson {
    private static let json = ModelJSON()

    /// `diagram` as JSON, exactly as it is: to save one clean, pass it through `DiagramNormalizer.serialize` first.
    public static func encode(_ diagram: Diagram) -> JSONObject { json.encodeObject(diagram) }

    /// A target as JSON: `{lat, lon, mgrs}`.
    public static func encode(_ target: DiagramTarget) -> JSONObject { json.encodeObject(target) }
}
