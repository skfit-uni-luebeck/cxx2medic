package de.uksh.medic.cxx2medic.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("replay", ignoreInvalidFields = false, ignoreUnknownFields = false)
class ReplaySettings(
    enabled: Boolean? = null,
    dataDir: String? = null
) {
    val enabled: Boolean = enabled ?: true
    val dataDir: Path = Path.of(dataDir ?: "data")
}