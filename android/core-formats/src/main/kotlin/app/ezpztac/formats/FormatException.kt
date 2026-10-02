package app.ezpztac.formats

/**
 * A file the app cannot read. The message is written for the person who chose the file
 * ("This .LPS file contains no readable points."), so a screen can show it as it is.
 */
public open class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
