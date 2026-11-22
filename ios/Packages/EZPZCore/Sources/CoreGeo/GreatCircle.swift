import Foundation

/// Distance and true course between two points on a sphere, as `routeCalc.js` computes them for route legs. The spherical model
/// (3,440.065 nm radius) is the planner's, not a geodesic's: matching the web matters more here than the fraction of a percent an
/// ellipsoid would add, because the same legs are exported to AMPS.
public enum GreatCircle {
    private static let earthRadiusNm = 3440.065
    private static let earthRadiusFt = 20_902_231.0

    private static func toRad(_ deg: Double) -> Double { deg * Double.pi / 180 }
    private static func toDeg(_ rad: Double) -> Double { rad * 180 / Double.pi }

    public static func distanceNm(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let dLat = toRad(lat2 - lat1)
        let dLon = toRad(lon2 - lon1)
        let sLat = sin(dLat / 2)
        let sLon = sin(dLon / 2)
        let a = sLat * sLat + cos(toRad(lat1)) * cos(toRad(lat2)) * sLon * sLon
        return 2 * earthRadiusNm * asin(a.squareRoot())
    }

    /// Distance in feet, by the haversine on the web's 20,902,231 ft sphere (`getDistanceFeet` in `utils/Helpers.js`). It is what the
    /// separation between two aircraft is measured with, and it is *not* the planner's sphere above: the two differ in the fifth digit,
    /// and each has to agree with the web where it is used.
    public static func distanceFeet(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let rad = Double.pi / 180
        let dLat = (lat2 - lat1) * rad
        let dLon = (lon2 - lon1) * rad
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(a.squareRoot(), (1 - a).squareRoot())
        return earthRadiusFt * c
    }

    /// Initial true course from the first point to the second, 0 up to 360 degrees.
    public static func trueCourseDeg(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let dLon = toRad(lon2 - lon1)
        let y = sin(dLon) * cos(toRad(lat2))
        let x = cos(toRad(lat1)) * sin(toRad(lat2)) - sin(toRad(lat1)) * cos(toRad(lat2)) * cos(dLon)
        return (toDeg(atan2(y, x)) + 360).truncatingRemainder(dividingBy: 360)
    }
}
