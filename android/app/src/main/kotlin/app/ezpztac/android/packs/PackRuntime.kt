package app.ezpztac.android.packs

import app.ezpztac.data.PackWorkspace
import app.ezpztac.missionpacks.DrainOutcome
import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackUser
import javax.inject.Inject
import javax.inject.Singleton

/** The feature key that turns mission packs on for an account (`entitlements.FEATURES`; off by default until they launch). */
const val MISSION_PACKS = "mission_packs"

/**
 * What the app asks of mission packs (docs/MISSION_PACKS.md): who they run for, whether the app is in front, that the connection is back,
 * and the background sync's part. The shell ([app.ezpztac.android.AppViewModel]) and the sync runner talk to this, so each is tried without
 * an engine.
 */
interface PackRuntime {
    /** Packs run for [user]: signed in, past the gate, Mission Packs on, and the plans on the device theirs. */
    suspend fun enable(user: PackUser)

    /**
     * Nobody may run packs here now (signed out, the feature off, another account's device): an item of a pack open in an editor is written
     * into it and closed, then everything stops, and nothing is given up.
     */
    suspend fun disable()

    /** Joins the editors to whichever pack is open. Called after every [enable]; doing it again changes nothing. */
    fun start()

    fun foreground(visible: Boolean)

    fun wake()

    suspend fun unsentCount(): Int

    suspend fun drainAll(user: PackUser): DrainOutcome
}

@Singleton
class EnginePackRuntime @Inject constructor(private val engine: PackEngine, private val workspace: PackWorkspace) : PackRuntime {
    override suspend fun enable(user: PackUser) = engine.enable(user)

    // Whatever of a pack is open in the editors is written into its pack and closed before the engine stops, whatever the reason: after,
    // its change could only be kept as the person's own.
    override suspend fun disable() = workspace.stop()

    // The editors follow whichever pack is open, and send what is changed in its items.
    override fun start() = workspace.start()

    override fun foreground(visible: Boolean) = engine.foreground(visible)

    override fun wake() = engine.wake()

    override suspend fun unsentCount(): Int = engine.unsentCount()

    override suspend fun drainAll(user: PackUser): DrainOutcome = engine.drainAll(user)
}
