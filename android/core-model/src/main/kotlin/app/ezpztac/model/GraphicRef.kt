package app.ezpztac.model

/**
 * Names one graphic in a diagram: its collection (`"helicopters"`, `"pzMarkers"` …) and its id as text ([DiagramOps.idText]). What the
 * person has selected, and what an edit is about. The diagram itself is not named: the open diagram is the only one with a selection.
 */
public data class GraphicRef(val collection: String, val key: String)
