package app.ezpztac.symbols

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * milsymbol, the library the web draws its symbols with, run in the system's JavaScript sandbox (`androidx.javascriptengine`): the answer for any
 * symbol that is not a pre-rendered preset. The sandbox has no DOM and needs none; the page script (assets/ezpz-render.js) is the one the web's
 * contract test runs against milsymbol in a context with no DOM.
 *
 * **Not verified here**: the sandbox needs a WebView with the JavaScript sandbox on a device, which a unit test does not have. What is checked
 * is everything round it (the script, the answer it gives and how it is read, the rasteriser, the cache and the fallback when this says
 * [SvgResult.NotHere]). The first run on a device should draw a unit with a designation.
 *
 * Starting the sandbox and loading milsymbol (about 860 KB) takes a moment, so it happens when the first symbol that needs it is asked for, once.
 * If the sandbox is not there (an old WebView, or the process was refused) every call answers [SvgResult.NotHere] until [RETRY_AFTER_MS] has
 * passed, then tries once more.
 */
class JavaScriptSymbolSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : SymbolSvgSource {
    private val lock = Mutex()
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    private var failedAt: Long? = null

    override suspend fun svg(spec: SymbolSpec): SvgResult {
        val isolate = ready() ?: return SvgResult.NotHere
        val answer = try {
            isolate.evaluateJavaScriptAsync(expression(spec)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            discard()                                   // the isolate may have been ended under us; the next call starts a fresh one
            return SvgResult.NotHere
        }
        return ScriptAnswer.parse(answer) ?: SvgResult.NotHere
    }

    private suspend fun ready(): JavaScriptIsolate? = lock.withLock {
        isolate?.let { return it }
        val failed = failedAt
        if (failed != null && clock() - failed < RETRY_AFTER_MS) return null
        try {
            if (!JavaScriptSandbox.isSupported()) return fail()
            val box = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).await()
            val fresh = box.createIsolate()
            sandbox = box
            load(box, fresh, "milsymbol.js")
            load(box, fresh, "ezpz-render.js")
            isolate = fresh
            failedAt = null
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            discard()
            fail()
        }
    }

    private fun fail(): JavaScriptIsolate? {
        failedAt = clock()
        return null
    }

    /**
     * Evaluates an asset in [isolate]. milsymbol is close to a megabyte, and a binder transaction is limited to about that, so unless the
     * sandbox says it can evaluate without the limit the script is sent in pieces, joined in the isolate and run there.
     */
    private suspend fun load(box: JavaScriptSandbox, isolate: JavaScriptIsolate, asset: String) {
        val source = context.assets.open(asset).use { it.readBytes().decodeToString() }
        if (source.length <= SINGLE_SHOT_LIMIT || box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            isolate.evaluateJavaScriptAsync(source).await()
            return
        }
        isolate.evaluateJavaScriptAsync("globalThis.__ezpzSource = '';").await()
        for (piece in source.chunked(PIECE)) {
            isolate.evaluateJavaScriptAsync("globalThis.__ezpzSource += ${Json.encodeToString(String.serializer(), piece)};").await()
        }
        isolate.evaluateJavaScriptAsync("(0, eval)(globalThis.__ezpzSource); delete globalThis.__ezpzSource;").await()
    }

    private fun discard() {
        runCatching { isolate?.close() }
        runCatching { sandbox?.close() }
        isolate = null
        sandbox = null
    }

    /** Ends the sandbox (the app is going away, or memory is short). The next symbol that needs it starts it again. */
    suspend fun close() = lock.withLock { discard() }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                } catch (e: java.util.concurrent.CancellationException) {
                    continuation.cancel(e)
                }
            },
            Runnable::run,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }

    companion object {
        /** The options milsymbol is given, as the web gives them (`size`, and the two labels when there are any), as JSON text. */
        internal fun options(spec: SymbolSpec): String = buildString {
            append("{\"size\":").append(spec.size)
            if (spec.uniqueDesignation.isNotEmpty()) append(",\"uniqueDesignation\":").append(Json.encodeToString(String.serializer(), spec.uniqueDesignation))
            if (spec.higherFormation.isNotEmpty()) append(",\"higherFormation\":").append(Json.encodeToString(String.serializer(), spec.higherFormation))
            append('}')
        }

        /**
         * What is evaluated in the sandbox to draw [spec]: a call of `ezpzRenderSymbol` with the SIDC and the options as JSON text, each as a
         * string literal. Nothing a person types is ever put into the script as code.
         */
        internal fun expression(spec: SymbolSpec): String =
            "ezpzRenderSymbol(${Json.encodeToString(String.serializer(), spec.sidc)}, ${Json.encodeToString(String.serializer(), options(spec))})"

        const val RETRY_AFTER_MS = 60_000L
        private const val SINGLE_SHOT_LIMIT = 200_000
        private const val PIECE = 150_000
    }
}
