package app.ezpztac.symbols

import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Draws MIL-STD-2525C symbols to bitmaps (docs/NATIVE_APPS_PLAN.md, "One `SymbolRenderer` service"). */
fun interface SymbolRenderer {
    /** [spec] as pixels, [scale] pixels to each unit of milsymbol's own drawing (the screen's density draws it as large as the web does). */
    suspend fun render(spec: SymbolSpec, scale: Float): SymbolOutcome
}

/**
 * The renderer: the pre-rendered presets first (no JavaScript, always there), then milsymbol in the sandbox for the rest, each rasterised by
 * AndroidSVG and kept in a small memory cache keyed by the symbol, its labels, its size and the scale.
 *
 * Only a symbol that was drawn is kept. One that the device could not draw (the sandbox was not there) is asked again next time, so it appears
 * once the sandbox does; a code that is not a symbol is remembered as such, for it will not become one.
 */
class DefaultSymbolRenderer(
    private val sources: List<SymbolSvgSource>,
    maxEntries: Int = 128,
) : SymbolRenderer {
    private val cache = object : LruCache<String, RenderedSymbol>(maxEntries) {}
    private val invalid = HashSet<String>()
    private val lock = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<SymbolOutcome>>()

    override suspend fun render(spec: SymbolSpec, scale: Float): SymbolOutcome {
        val key = "${spec.sidc}|${spec.uniqueDesignation}|${spec.higherFormation}|${spec.size}|$scale"
        val mine = CompletableDeferred<SymbolOutcome>()
        val existing = lock.withLock {
            cache[key]?.let { return SymbolOutcome.Drawn(it) }
            if (key in invalid) return SymbolOutcome.Invalid
            inFlight[key] ?: run { inFlight[key] = mine; null }
        }
        if (existing != null) return existing.await()                       // someone is already drawing it: wait for that one
        val outcome = try {
            draw(spec, scale)
        } catch (e: Throwable) {
            lock.withLock { inFlight.remove(key) }
            mine.completeExceptionally(e)
            throw e
        }
        lock.withLock {
            when (outcome) {
                is SymbolOutcome.Drawn -> cache.put(key, outcome.symbol)
                SymbolOutcome.Invalid -> invalid += key
                SymbolOutcome.Unavailable -> Unit
            }
            inFlight.remove(key)
        }
        mine.complete(outcome)
        return outcome
    }

    private suspend fun draw(spec: SymbolSpec, scale: Float): SymbolOutcome {
        for (source in sources) {
            when (val result = source.svg(spec)) {
                is SvgResult.Found -> return SvgRasterizer.rasterize(result.svg, scale)?.let { SymbolOutcome.Drawn(it) } ?: SymbolOutcome.Invalid
                SvgResult.Invalid -> return SymbolOutcome.Invalid
                SvgResult.NotHere -> Unit
            }
        }
        return SymbolOutcome.Unavailable
    }
}
