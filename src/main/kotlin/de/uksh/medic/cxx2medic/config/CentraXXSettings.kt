package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import de.uksh.medic.cxx2medic.util.parseIdentifierToken
import org.hl7.fhir.r4.model.Identifier
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cxx")
class CentraXXSettings(
    val database: DatabaseSettings,
    val fhir: FhirSettings,
    val criteriaFile: String,
    managingOrg: String,
    val patientReferenceIdentifier: String?
) {
    val managingOrg: Identifier = parseIdentifierToken(managingOrg).getOrThrow()
}

class FhirSettings(
    val url: String,
    authentication: AuthenticationSettings? = null,
    authorization: AuthorizationSettings? = null
) {
    val authentication: Option<AuthenticationSettings> = if (authentication == null) None else Some(authentication)
    val authorization: Option<AuthorizationSettings> = if (authorization == null) None else Some(authorization)
}