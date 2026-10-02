package app.ezpztac.sync

import kotlin.math.min
import kotlin.math.pow

/**
 * How long to wait before the next sync attempt after one that did not finish. The engine only records that an entry was tried;
 * the scheduler (WorkManager in the app) asks this when to try again.
 */
public object RetryPolicy {
    private const val BASE_MS = 5_000.0
    private const val CAP_MS = 15 * 60 * 1000.0

    /**
     * Doubles from five seconds to a cap of fifteen minutes, with [jitter] (0.0 to 1.0) adding up to 20% so a fleet of devices that
     * all lost signal together does not come back together.
     */
    public fun delayMillis(failures: Int, jitter: Double = 0.0): Long {
        require(failures >= 0) { "failures cannot be negative" }
        val base = min(CAP_MS, BASE_MS * 2.0.pow(min(failures, 30)))
        return (base * (1.0 + 0.2 * jitter.coerceIn(0.0, 1.0))).toLong()
    }
}
