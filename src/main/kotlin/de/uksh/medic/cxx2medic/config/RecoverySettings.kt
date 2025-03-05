package de.uksh.medic.cxx2medic.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("recovery")
class RecoverySettings(
    dir: String?
) {
    val file = Path.of(dir ?: "data", "recovery.json")
}