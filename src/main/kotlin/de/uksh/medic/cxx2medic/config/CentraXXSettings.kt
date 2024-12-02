package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cxx")
data class CentraXXSettings(
    val database: DatabaseSettings,
    val fhir: FhirSettings
)

class FhirSettings(
    val url: String,
    authentication: AuthenticationSettings? = null,
    authorization: AuthorizationSettings? = null
) {
    val authentication: Option<AuthenticationSettings> = if (authentication == null) None else Some(authentication)
    val authorization: Option<AuthorizationSettings> = if (authorization == null) None else Some(authorization)
}