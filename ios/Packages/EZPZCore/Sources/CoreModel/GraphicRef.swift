/// Names one graphic in a diagram: its collection (`"helicopters"`, `"pzMarkers"` …) and its id as text (`DiagramOps.idText`). What
/// the person has selected, and what an edit is about. The diagram itself is not named: the open diagram is the only one with a
/// selection.
public struct GraphicRef: Hashable, Sendable {
    public var collection: String
    public var key: String

    public init(collection: String, key: String) {
        self.collection = collection
        self.key = key
    }
}
