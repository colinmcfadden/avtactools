/// The strips at the `top` and `bottom` of the map that the app's own controls cover, in points: the search bar, and the sheet at the
/// height it rests at. The camera's centre, where the crosshair is drawn and what the readout names, is the middle of what is between
/// them. (Android's `MapPadding` in MapHost.kt, in dp.)
public struct MapPadding: Hashable, Sendable {
    public var top: Double
    public var bottom: Double

    public init(top: Double = 0.0, bottom: Double = 0.0) {
        self.top = top
        self.bottom = bottom
    }
}
