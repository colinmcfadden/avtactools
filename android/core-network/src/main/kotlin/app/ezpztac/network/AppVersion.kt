package app.ezpztac.network

/**
 * An app version, `major.minor.patch` with an optional `-suffix`, compared the way the server's
 * `minAppVersion` is meant: the numbers first, and a pre-release (`1.4.0-beta.2`) below the release
 * of the same numbers.
 */
public class AppVersion private constructor(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
    private val preRelease: String?,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1                                  // a release is above its own pre-releases
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, preRelease).hashCode()
    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    public companion object {
        private val PATTERN = Regex("""^(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:-([0-9A-Za-z.]{1,20}))?$""")

        /** The version in [text], or null if it is not one. */
        public fun parse(text: String?): AppVersion? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            return AppVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { null })
        }
    }
}

/**
 * Whether the server no longer supports this app: [version] is below the minimum it set for [platform]. No
 * minimum (or one this cannot read) means supported: an unreadable value must never lock everyone out.
 */
public fun AppConfig.updateRequired(platform: String, version: String): Boolean = isBelowMinimum(
    version,
    when (platform) {
        "android" -> minAppVersion.android
        "ios" -> minAppVersion.ios
        else -> null
    },
)

/**
 * Whether [version] is below [minimum], a minimum the server gave (perhaps some time ago, as the device remembers it). No minimum, or
 * either one unreadable, is not: an unreadable value must never lock everyone out.
 */
public fun isBelowMinimum(version: String, minimum: String?): Boolean {
    val floor = AppVersion.parse(minimum) ?: return false
    val current = AppVersion.parse(version) ?: return false
    return current < floor
}
