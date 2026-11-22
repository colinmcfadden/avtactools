/// One corner of a diagram's boundary: the `index`th point of the boundary the person drew and has not analysed (`drawn` true,
/// `analysis.customLZ`), or of the analysis boundary (`drawn` false, `analysis.detectedLZ`, which is the drawn one once it has been
/// analysed). A position in a list, so it names a corner only until a corner is deleted.
public struct BoundaryCornerRef: Hashable, Sendable {
    public var drawn: Bool
    public var index: Int

    public init(drawn: Bool, index: Int) {
        self.drawn = drawn
        self.index = index
    }
}
