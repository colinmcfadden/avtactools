package app.ezpztac.planning

import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.PointOverride
import app.ezpztac.model.RoutePlan
import app.ezpztac.model.RoutePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Plan settings seeded from a profile and restored from older saves (`defaultRoutePlan` and `ensureRoutePlan`). */
public object RoutePlans {
    private val json = Json {
        ignoreUnknownKeys = true
        // A null where a value is required reads as the default; the web would
        // fail later on it, and refusing the whole saved route is worse.
        coerceInputValues = true
        encodeDefaults = true
    }

    /** A fresh plan seeded from an aircraft profile; without one, the UH-60L values the planner has always used. */
    public fun default(profile: AircraftProfile? = null): RoutePlan = RoutePlan.forAircraft(profile ?: AircraftProfile.FALLBACK)

    /**
     * Backfills plan defaults on a route restored from an older save, and migrates
     * the pre-inline `tot` anchor (a single TOT field) into a per-point clock.
     *
     * Like the web's `{ ...defaultRoutePlan(), ...route.plan }`, saved values win
     * over defaults key by key, and a `perPoint` in the save replaces the default's
     * wholesale. A clock already on a point wins over a leftover `tot`.
     *
     * @param points the route's points, to find the first AMPS point when `tot` names none
     * @param savedPlan the route's `plan` as it was saved, or null for a route without one
     */
    public fun ensure(points: List<RoutePoint>, savedPlan: JsonObject?): RoutePlan {
        val merged = LinkedHashMap<String, JsonElement>(json.encodeToJsonElement(default()).jsonObject)
        savedPlan?.forEach { (key, value) -> merged[key] = value }
        merged.remove("tot")
        var plan = json.decodeFromJsonElement<RoutePlan>(JsonObject(merged))

        val tot = savedPlan?.get("tot") as? JsonObject
        val time = tot?.text("time")
        if (!time.isNullOrEmpty() && plan.perPoint.values.none { !it.clock.isNullOrEmpty() }) {
            tot.text("date")?.takeIf { it.isNotEmpty() }?.let { plan = plan.copy(date = it) }
            val anchorId = tot.text("pointId")?.takeIf { it.isNotEmpty() } ?: RouteCalc.planPoints(points).firstOrNull()?.id
            if (anchorId != null) {
                val anchored = (plan.perPoint[anchorId] ?: PointOverride()).copy(clock = time)
                plan = plan.copy(perPoint = plan.perPoint + (anchorId to anchored))
            }
        }
        return plan
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
