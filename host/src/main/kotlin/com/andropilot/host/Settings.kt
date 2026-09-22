package com.andropilot.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the host remembers between runs.
 *
 * Every field is optional. The file says what you have decided; anything absent keeps its
 * default, and anything on the command line wins over both. That ordering is the whole
 * point -- a settings file that could not be overridden for one run would be worse than
 * typing the flags.
 *
 * It lives in the home directory rather than beside the checkout because the token is in
 * it. A token stored in the working copy is lost the moment the repository is moved or
 * re-cloned, and losing it means reconfiguring the phone by hand.
 */
@Serializable
public data class HostSettings(
    val port: Int? = null,
    val bind: String? = null,
    /** The shared secret the phone presents. Generated once and then left alone. */
    val token: String? = null,
    @SerialName("ingestPort") val ingestPort: Int? = null,
    @SerialName("uiPort") val uiPort: Int? = null,
    val skills: String? = null,
    @SerialName("telemetryDir") val telemetryDir: String? = null,
    @SerialName("modelEndpoint") val modelEndpoint: String? = null,
    /**
     * What to ask the endpoint for: a model id, or the name of a gateway construct that
     * stands in for one -- an OmniRoute combo, for instance, is addressed by its own name
     * and picks a model behind it. The host does not care which; it sends the string.
     */
    @SerialName("modelId") val modelId: String? = null,
    /** Read when `modelId` is absent, for files written before the field was renamed. */
    @SerialName("model") val legacyModel: String? = null,
    /**
     * The model API key.
     *
     * Allowed here because the alternative is setting an environment variable before every
     * run, which is the sort of friction that ends with the key on a command line instead --
     * where anything that can list processes reads it. `ANDROPILOT_MODEL_KEY` still wins if
     * it is set, and the file is created with owner-only permissions.
     */
    @SerialName("apiKey") val apiKey: String? = null,
    /** Read when `apiKey` is absent, for files written before the field was renamed. */
    @SerialName("modelKey") val legacyModelKey: String? = null,
    val temperature: Double? = null,
    @SerialName("maxSteps") val maxSteps: Int? = null,
) {
    public companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

        /** `~/.andropilot-host/settings.json` unless `--config` says otherwise. */
        public fun defaultPath(): File =
            File(System.getProperty("user.home"), ".andropilot-host/settings.json")

        /**
         * Reads the file, or returns defaults when it is not there.
         *
         * A malformed file throws rather than being ignored. Silently falling back to
         * defaults would start the host on the wrong port with a freshly invented token,
         * and the only symptom would be a phone that no longer connects.
         */
        public fun read(file: File): HostSettings {
            if (!file.isFile) return HostSettings()
            val text = file.readText()
            if (text.isBlank()) return HostSettings()
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw IllegalStateException("${file.path} is not valid settings JSON: ${e.message}")
            }
        }

        public fun write(file: File, settings: HostSettings) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), settings))
            // Best effort: POSIX only, and a no-op on Windows, where the home directory is
            // already per-user. The file holds a token and possibly a model key.
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
        }
    }
}

/**
 * Folds a settings file under the command line.
 *
 * Applied to the options parsed from arguments, so anything explicitly typed survives. The
 * comparison is against each field's default: a flag left off is indistinguishable from one
 * set to the default value, and preferring the file in that case is the behaviour somebody
 * wants when they wrote the file down.
 */
internal fun Options.withDefaultsFrom(settings: HostSettings): Options {
    val fallback = Options()
    return copy(
        port = if (port != fallback.port) port else settings.port ?: port,
        bind = if (bind != fallback.bind) bind else settings.bind ?: bind,
        token = token ?: settings.token,
        ingestPort = ingestPort ?: settings.ingestPort,
        uiPort = uiPort ?: settings.uiPort,
        skillsDirectory = if (skillsDirectory != fallback.skillsDirectory) skillsDirectory
        else settings.skills?.let(::File) ?: skillsDirectory,
        telemetryDirectory = if (telemetryDirectory != fallback.telemetryDirectory) telemetryDirectory
        else settings.telemetryDir?.let(::File) ?: telemetryDirectory,
        modelEndpoint = modelEndpoint ?: settings.modelEndpoint,
        model = if (model != fallback.model) model
        else settings.modelId ?: settings.legacyModel ?: model,
        modelKey = modelKey ?: settings.apiKey ?: settings.legacyModelKey,
        temperature = temperature ?: settings.temperature,
        maxSteps = if (maxSteps != fallback.maxSteps) maxSteps else settings.maxSteps ?: maxSteps,
    )
}
