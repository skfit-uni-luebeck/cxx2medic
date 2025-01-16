package de.uksh.medic.cxx2medic.config

import java.net.URI
import java.nio.file.Path

class TruststoreSettings(
    location: String,
    val password: String
) {
    val location = Path.of(URI.create(location))
}