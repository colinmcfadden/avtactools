package app.ezpztac.data

/** The names exported files are given. */
internal object ExportNames {
    private const val MAX_NAME = 80

    /** [name] made safe as the start of a file name: anything a file system or a chat app would trip over is an underscore, never empty ([fallback] then). */
    fun stem(name: String, fallback: String): String {
        val base = name
            .map { if (it.isLetterOrDigit() || it in " -_.") it else '_' }.joinToString("")
            .replace(Regex("""\.{2,}"""), "_")
            .trim(' ', '.', '_')
            .take(MAX_NAME).trim(' ', '.', '_')
        return base.ifEmpty { fallback }
    }
}
