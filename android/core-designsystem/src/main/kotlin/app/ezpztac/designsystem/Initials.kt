package app.ezpztac.designsystem

/**
 * The letters on a person's face (the account button, the faces in a pack's chip): the first of the first and last words of [name] ("Colin McFadden" is CM,
 * "Cher" is C), or the first of [email] when there is no name. Letters and digits only, by whole characters (an emoji is never cut, and is not a letter),
 * in capitals; "?" when neither has one.
 */
fun initialsOf(name: String, email: String = ""): String {
    fun first(word: String): String? {
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return String(Character.toChars(Character.toUpperCase(cp)))
            i += Character.charCount(cp)
        }
        return null
    }
    val words = name.split(' ', '\t', '\n').map { it.trim() }.filter { first(it) != null }
    val fromName = when (words.size) {
        0 -> ""
        1 -> first(words[0]).orEmpty()
        else -> first(words.first()).orEmpty() + first(words.last()).orEmpty()
    }
    return fromName.ifEmpty { first(email) ?: "?" }
}
