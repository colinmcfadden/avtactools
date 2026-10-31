package app.ezpztac.sync

/**
 * Settles a conflict the engine kept beside a record. A seam, so a screen that offers the choice is tried without an engine; the engine
 * is the implementation.
 */
public fun interface ConflictResolver {
    public suspend fun resolve(kind: RecordKind, copyUuid: String, resolution: SyncEngine.Resolution)
}
