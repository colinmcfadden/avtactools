package app.ezpztac.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Loads the golden fixtures from `contracts/fixtures`, the files the web,
 * backend and iOS tests read too. Gradle passes the folder in as the
 * `ezpz.contracts` system property (see the `ezpz.kotlin-library` plugin).
 */
public object Fixtures {
    private val directory: File by lazy {
        val path = System.getProperty("ezpz.contracts")
            ?: error("ezpz.contracts is not set; run the tests through Gradle")
        File(path).also { check(it.isDirectory) { "No fixtures at $path" } }
    }

    public val json: Json = Json { ignoreUnknownKeys = true }

    public fun load(name: String): JsonObject {
        val file = File(directory, name)
        check(file.isFile) { "Fixture $name is missing at ${file.path}" }
        return json.parseToJsonElement(file.readText()) as JsonObject
    }

    public fun text(name: String): String = File(directory, name).readText()

    /** A binary fixture, such as a `.msnx` mission file. */
    public fun bytes(name: String): ByteArray {
        val file = File(directory, name)
        check(file.isFile) { "Fixture $name is missing at ${file.path}" }
        return file.readBytes()
    }
}

public fun JsonElement.asObject(): JsonObject = this as JsonObject
