package app.ezpztac.data

/**
 * The LZ/PZ the 3D view is shown on: its identity, its name as the LZ/PZ panel shows it (the view's title, `3D · NAME`), its target, and whether it
 * is saved (named and analysed, outside a pack: the web's `savedId`), which keeps its point cloud building when the view closes.
 */
public data class Viewer3DTarget(val uuid: String, val name: String, val lat: Double, val lon: Double, val saved: Boolean)
