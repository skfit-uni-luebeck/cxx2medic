package de.uksh.medic.cxx2medic.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cxx")
data class CentraXXSettings(
    val database: DatabaseSettings,
    val fhir: FhirSettings
)

data class FhirSettings(
    val url: String,
    val username: String,
    val password: String
)