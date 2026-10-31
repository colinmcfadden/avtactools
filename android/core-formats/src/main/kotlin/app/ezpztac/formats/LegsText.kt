package app.ezpztac.formats

/**
 * `legs.xml` edited as **text**, never as a DOM.
 *
 * A real mission's `legs.xml` is most of the file (tens of megabytes: every leg carries calculation blobs). The app wants to change three short
 * things in it, and a DOM of it would cost far more memory than a phone has to spare, so this finds each `<leg>…</leg>` once and changes only the
 * text of the leg it is asked about. Everything else is written back exactly as it came.
 *
 * What it can do is what the web's `mutateMsnx.js` does to the legs part:
 * - set one value of one leg (`AirspeedValue`, `CruiseWind`, `ClimbDescentWind`),
 * - split a leg in two (inserting a point on it), which clones the whole leg, as the web does,
 * - move the leg trackpoint that mirrors a dragged serpentine point.
 *
 * It trusts the same shape the reader does: the first `<id>`, `<startpt>` and `<endpt>` in a leg are the leg's own.
 */
internal class LegsText(private val text: String) {
    /** A piece of the document: a stretch of the original, or text made here. Edited text replaces the stretch. */
    private class Piece(val start: Int, val end: Int, var edited: String? = null, val isLeg: Boolean = false) {
        var id: String? = null
        fun body(master: String): String = edited ?: master.substring(start, end)
    }

    private val pieces = ArrayList<Piece>()
    private val byId = HashMap<String, Piece>()

    init {
        var cursor = 0
        while (true) {
            val open = text.indexOf(LEG_OPEN, cursor)
            if (open < 0) break
            val close = text.indexOf(LEG_CLOSE, open)
            if (close < 0) break
            val end = close + LEG_CLOSE.length
            // What lies between the legs (nothing, or the whitespace a formatter left) is its own piece, so the legs can be changed without touching it.
            if (open > cursor) pieces += Piece(cursor, open)
            val leg = Piece(open, end, isLeg = true)
            leg.id = textOf(leg, "id")
            pieces += leg
            leg.id?.takeIf { it.isNotEmpty() }?.let { byId.putIfAbsent(it, leg) }
            cursor = end
        }
        pieces += Piece(cursor, text.length)
    }

    fun hasLeg(legId: String): Boolean = legId in byId

    fun startOf(legId: String): String? = byId[legId]?.let { textOf(it, "startpt") }

    fun endOf(legId: String): String? = byId[legId]?.let { textOf(it, "endpt") }

    /** Sets the value of the first item with this key in the leg, as the DOM version does. A leg or an item that is not there is left alone. */
    fun setItemValue(legId: String, key: String, value: String) {
        val piece = byId[legId] ?: return
        val body = piece.body(text)
        val keyAt = body.indexOf("<key>$key</key>")
        if (keyAt < 0) return
        val valueAt = body.indexOf("<value", keyAt)
        if (valueAt < 0) return
        val replaced = valueElement(body, valueAt, value) ?: return
        piece.edited = replaced
    }

    /**
     * Splits [legId] in two. The new leg is a copy of the whole leg (the web's `cloneNode(true)`) that starts at [newPointId] and has [newLegId] for an id, placed straight
     * after the original; the original now ends at [newPointId].
     */
    fun splitLeg(legId: String, newLegId: String, newPointId: String) {
        val piece = byId[legId] ?: return
        val original = piece.body(text)
        val clone = setChild(setChild(original, "id", newLegId), "startpt", newPointId)
        piece.edited = setChild(original, "endpt", newPointId)
        val made = Piece(0, 0, edited = clone, isLeg = true)
        made.id = newLegId
        // Straight after the leg and before the whitespace that follows it, as the DOM's `insertBefore(nextSibling)` puts it.
        pieces.add(pieces.indexOf(piece) + 1, made)
        byId[newLegId] = made
    }

    /** Rewrites the first trackpoint whose coordinate reads [oldCoord] to [newCoord], as the web does when a serpentine point is dragged. */
    fun swapTrackpoint(oldCoord: String, newCoord: String) {
        for (piece in pieces) {
            if (!piece.isLeg) continue
            val body = piece.body(text)
            var from = 0
            while (true) {
                val keyAt = body.indexOf(TRACK_KEY, from)
                if (keyAt < 0) break
                val valueAt = body.indexOf("<value>", keyAt)
                if (valueAt < 0) break
                val close = body.indexOf("</value>", valueAt)
                if (close < 0) break
                val current = body.substring(valueAt + "<value>".length, close)
                if (current == oldCoord) {
                    piece.edited = body.substring(0, valueAt) + "<value>" + escape(newCoord) + body.substring(close)
                    return
                }
                from = close
            }
        }
    }

    fun build(): String {
        val out = StringBuilder(text.length + 1024)
        for (piece in pieces) out.append(piece.body(text))
        return out.toString()
    }

    // -- the text of one leg --------------------------------------------------------------------------------------------

    private fun textOf(piece: Piece, tag: String): String? {
        val body = if (piece.edited != null) piece.edited!! else null
        val source = body ?: text
        val limitStart = if (body != null) 0 else piece.start
        val limitEnd = if (body != null) body.length else piece.end
        val open = source.indexOf("<$tag>", limitStart)
        if (open < 0 || open >= limitEnd) return null
        val from = open + tag.length + 2
        val close = source.indexOf("</$tag>", from)
        if (close < 0 || close > limitEnd) return null
        return source.substring(from, close)
    }

    /** Replaces the text of the first `<tag>` in [body] (an element written empty, `<tag/>`, is filled). */
    private fun setChild(body: String, tag: String, value: String): String {
        val open = body.indexOf("<$tag>")
        if (open >= 0) {
            val from = open + tag.length + 2
            val close = body.indexOf("</$tag>", from)
            if (close >= 0) return body.substring(0, from) + escape(value) + body.substring(close)
        }
        val empty = body.indexOf("<$tag/>")
        if (empty >= 0) return body.substring(0, empty) + "<$tag>" + escape(value) + "</$tag>" + body.substring(empty + tag.length + 3)
        return body
    }

    /** The leg with the `<value>` element at [valueAt] given [value]: the text of `<value>old</value>`, or `<value/>` filled. */
    private fun valueElement(body: String, valueAt: Int, value: String): String? {
        val afterTag = valueAt + "<value".length
        return when {
            body.startsWith("/>", afterTag) -> body.substring(0, valueAt) + "<value>" + escape(value) + "</value>" + body.substring(afterTag + 2)
            body.startsWith(">", afterTag) -> {
                val close = body.indexOf("</value>", afterTag)
                if (close < 0) null else body.substring(0, afterTag + 1) + escape(value) + body.substring(close)
            }
            else -> null                                         // `<valueX …>`: some other element
        }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }

    private companion object {
        const val LEG_OPEN = "<leg>"
        const val LEG_CLOSE = "</leg>"
        const val TRACK_KEY = "<key>TrackPtCoordinate</key>"
    }
}
