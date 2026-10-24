package app.ezpztac.network

/** How a client build stands with the API a server serves (`contracts/scripts/api_version.py`). */
public enum class ApiStanding {
    /** The server serves a major this build talks: everything works. */
    SUPPORTED,

    /** The server has moved past every major this build talks: the way out is an update. */
    APP_TOO_OLD,

    /** This build talks only majors newer than any the server serves: the way out is the server's update, not the app's. */
    SERVER_TOO_OLD,
}

/**
 * The API majors a client build can talk to (`ezpz.apiMajors` in `android/gradle.properties`, held to what the server serves by
 * `contracts/scripts/api_version.py check`). A major is the server's promise that nothing this build reads or sends stops working.
 */
public class ApiSupport(majors: Collection<Int>) {
    public val majors: Set<Int> = majors.toSortedSet()

    init {
        require(this.majors.isNotEmpty() && this.majors.all { it > 0 }) { "an API support list needs whole majors above 0" }
    }

    /**
     * Where this build stands with a server serving [served]. Nothing known (no config yet, or a list this cannot read) is
     * [ApiStanding.SUPPORTED]: an unreadable value must never lock a crew out, as with the minimum version.
     */
    public fun standingWith(served: Collection<Int>?): ApiStanding = when {
        served.isNullOrEmpty() || served.any { it in majors } -> ApiStanding.SUPPORTED
        served.max() > majors.max() -> ApiStanding.APP_TOO_OLD
        else -> ApiStanding.SERVER_TOO_OLD
    }

    override fun toString(): String = majors.joinToString(",")

    public companion object {
        /** The comma-separated majors a build was made with (`BuildConfig.SUPPORTED_API_MAJORS`). */
        public fun parse(text: String): ApiSupport = ApiSupport(text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() })
    }
}

/**
 * The API majors this config says the server serves: its list, else its version's major, else 1, for a server from before either
 * field (API 1 is what those served). A version this cannot read is null: nothing known, so nothing is held back.
 */
public val AppConfig.servedApiMajors: List<Int>?
    get() {
        apiMajors?.takeIf { it.isNotEmpty() }?.let { return it }
        val version = apiVersion ?: return listOf(1)
        return Regex("""^(\d{1,4})\.\d{1,4}\.\d{1,4}$""").matchEntire(version.trim())?.groupValues?.get(1)?.toInt()?.let(::listOf)
    }
