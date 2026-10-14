package app.ezpztac.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A threat file that was brought into the picture: its name, and how many threats it held then. */
data class ThreatFileImport(val name: String, val count: Int)

/**
 * The threat files brought in (the Imports panel's *Threat files*, docs/native-design: Imports): in memory, as the threats they came with are on the device
 * only. The list goes with the picture: whatever was recorded before the picture was last wiped (a sign-out, *Remove all*) is not listed, since those
 * threats are gone.
 */
@Singleton
class ThreatFileLog @Inject constructor(private val store: ThreatStore) {
    private val entries = MutableStateFlow<List<Pair<Int, ThreatFileImport>>>(emptyList())

    /** The files brought in since the picture was last wiped, in the order they came. */
    val files: Flow<List<ThreatFileImport>> = combine(entries, store.wipes) { list, wipes -> list.filter { it.first == wipes }.map { it.second } }

    /** [name] brought [count] threats into the picture. */
    fun record(name: String, count: Int) {
        val now = store.wipes.value
        entries.update { list -> list.filter { it.first == now } + (now to ThreatFileImport(name, count)) }
    }
}
