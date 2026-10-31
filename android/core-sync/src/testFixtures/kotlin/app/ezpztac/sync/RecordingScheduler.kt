package app.ezpztac.sync

/** A [SyncScheduler] that only counts what it was asked, for tests of what asks. */
public class RecordingScheduler : SyncScheduler {
    public var requested: Int = 0
        private set
    public var periodic: Int = 0
        private set
    public var cancelled: Int = 0
        private set

    override fun requestSync() { requested++ }
    override fun schedulePeriodic() { periodic++ }
    override fun cancelAll() { cancelled++ }
}
