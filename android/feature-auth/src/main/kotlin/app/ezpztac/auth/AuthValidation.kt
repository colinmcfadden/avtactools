package app.ezpztac.auth

/** The checks made on the device before a request is sent, so a typo does not cost a round trip. The server checks again; its words win. */
object AuthValidation {
    /** The server's rule (`PASSWORD_MIN_LENGTH` in `backend/routes/auth.py`): a passphrase, not a word. */
    const val PASSWORD_MIN_LENGTH = 15
    const val PASSWORD_MAX_LENGTH = 128

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val MIL = Regex("^[^\\s@]+@[^\\s@]+\\.mil$", RegexOption.IGNORE_CASE)

    fun emailError(email: String): String? = when {
        email.isBlank() -> "Enter your email address."
        email.length > 120 || !EMAIL.matches(email.trim()) -> "Enter a valid email address."
        else -> null
    }

    fun milEmailError(email: String): String? = when {
        email.isBlank() -> "Enter your .mil email address."
        email.length > 120 || !MIL.matches(email.trim()) -> "Enter a valid .mil email address."
        else -> null
    }

    fun nameError(name: String): String? = when {
        name.isBlank() -> "Enter your name."
        name.trim().length > 120 -> "Use 120 characters or fewer."
        else -> null
    }

    /** For a password being chosen. (Signing in with one never checks its length: an old short one must still work.) */
    fun newPasswordError(password: String): String? = when {
        password.length < PASSWORD_MIN_LENGTH -> "Use a password of at least $PASSWORD_MIN_LENGTH characters. A passphrase works well."
        password.length > PASSWORD_MAX_LENGTH -> "Use $PASSWORD_MAX_LENGTH characters or fewer."
        else -> null
    }

    fun confirmationError(password: String, confirmation: String): String? =
        if (password != confirmation) "The passwords do not match." else null

    fun codeError(code: String): String? = if (code.isBlank()) "Enter the code from the email." else null

    /** "pilot@example.com" → "pi•••@example.com": enough to recognise, not enough to read over a shoulder. */
    fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at < 1) return "your email address"
        val local = email.substring(0, at)
        val visible = local.take(2)
        return visible + "•".repeat(maxOf(3, local.length - visible.length)) + email.substring(at)
    }
}
