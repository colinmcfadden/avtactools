package app.ezpztac.network

/**
 * Which app is calling, sent as `X-EZPZ-Client` on every request: `platform/version (build)`, for
 * example `android/1.4.0 (212)`. The server records it against sign-ins so the owner can see which
 * versions are in the field before changing an endpoint, and issues refresh tokens only to the
 * native platforms. A value that does not match the server's pattern is ignored there, so this
 * refuses to build one.
 */
public class ClientInfo(
    public val platform: String,
    /** `major.minor.patch`, with an optional `-suffix`. */
    public val version: String,
    public val build: Int? = null,
) {
    public val header: String = buildString {
        append(platform).append('/').append(version)
        if (build != null) append(" (").append(build).append(')')
    }

    init {
        require(PATTERN.matches(header) && header.length <= MAX_LENGTH) { "Not a valid X-EZPZ-Client value: \"$header\"" }
    }

    public companion object {
        public const val HEADER: String = "X-EZPZ-Client"

        private const val MAX_LENGTH = 64
        private val PATTERN = Regex("""^(android|ios|web)/\d{1,4}\.\d{1,4}\.\d{1,4}(-[0-9A-Za-z.]{1,20})?( \(\d{1,9}\))?$""")

        public fun android(version: String, build: Int? = null): ClientInfo = ClientInfo("android", version, build)
    }
}
