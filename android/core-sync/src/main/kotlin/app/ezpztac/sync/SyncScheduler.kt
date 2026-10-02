package app.ezpztac.sync

/** When the app's saved records are sent to and read from the server. The work itself is done by the sync engine. */
public interface SyncScheduler {
    /** Sync as soon as there is a connection: after an edit, at launch, after signing in. Asking while one is queued does nothing more. */
    public fun requestSync()

    /** Also sync now and then in the background, whether or not the app is open. */
    public fun schedulePeriodic()

    /** Stop everything (signed out). */
    public fun cancelAll()
}
