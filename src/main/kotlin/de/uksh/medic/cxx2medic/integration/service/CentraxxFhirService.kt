package de.uksh.medic.cxx2medic.integration.service

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor
import de.uksh.medic.cxx2medic.authentication.OAuthClientCredentials
import de.uksh.medic.cxx2medic.authentication.OAuthPasswordCredentials
import de.uksh.medic.cxx2medic.authentication.OAuthRefreshTokenCredentials
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Consent
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Specimen
import de.uksh.medic.cxx2medic.config.FhirSettings
import de.uksh.medic.cxx2medic.evaluation.b
import org.apache.http.auth.AuthenticationException
import org.apache.http.auth.Credentials
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import usr.paulolaup.fhir.client.interceptor.oauth.ClientCredentialsOAuthInterceptor
import usr.paulolaup.fhir.client.interceptor.oauth.PasswordOAuthInterceptor
import usr.paulolaup.fhir.client.interceptor.oauth.RefreshTokenOAuthInterceptor
import kotlin.reflect.full.companionObject

@Service
class CentraXXFhirService(
    @Autowired private val fhirContext: FhirContext,
    @Autowired @Qualifier("cxx:fhir-settings") settings: FhirSettings
)
{
    private val url: String = settings.url
    private val client: IGenericClient = fhirContext.apply {
        restfulClientFactory.serverValidationMode = ServerValidationModeEnum.ONCE
    }.newRestfulGenericClient(url)

    init
    {
        // Activate OAuth authorization if settings reflect its presence
        settings.authorization.onSome { it ->
            it.basic.fold (
                { it.oauth.fold(
                    { throw AuthenticationException("No Authentication data provided in authentication config") },
                    { oauth ->
                        client.registerInterceptor(when (val credentials = oauth.getCredentials()) {
                            is OAuthClientCredentials ->
                                ClientCredentialsOAuthInterceptor(oauth.accessTokenUrl, credentials.clientCredentials)
                            is OAuthRefreshTokenCredentials ->
                                RefreshTokenOAuthInterceptor(oauth.accessTokenUrl, credentials.clientCredentials)
                            is OAuthPasswordCredentials ->
                                PasswordOAuthInterceptor(
                                    oauth.accessTokenUrl, credentials.clientCredentials, credentials.usernameAndPassword
                                )
                        })
                    }
                ) },
                { basic -> client.registerInterceptor(BasicAuthInterceptor(basic.userName, basic.password)) }
            )
        }
    }

    final inline fun <reified T: IBaseResource> read(id: String): Result<T> =
        read(id, T::class.java)

    fun <T: IBaseResource> read(id: String, clazz: Class<T>): Result<T> =
        kotlin.runCatching { client.read().resource(clazz).withId(id).execute() }

    fun read(id: String, type: String): Result<IBaseResource> =
        kotlin.runCatching { client.read().resource(type).withId(id).execute() }

    @Cacheable("fhirPatientCache", cacheManager = "cacheManager")
    fun readPatient(id: String): Option<Patient> =
        (read(id, "Patient") as Result<Patient>).fold(
            { p -> Some(p) },
            { e -> logger.warn("Failed to retrieve Patient resource [id=$id]: ${e.message}"); logger.debug(e); None }
        )

    // Caches for Consent resources are currently deactivated since they are checked for their policies and changes to
    // them are crucial to detect. Consequently, those resources should be kept up to date. Alternatively one could
    // reset the cache before each run to at least cache resources within a single run
    @Cacheable("fhirConsentCache", cacheManager = "cacheManager")
    fun readConsent(id: String): Option<Consent> =
        (read(id, "Consent") as Result<Consent>).fold(
            { c -> Some(c) },
            { e -> logger.warn("Failed to retrieve Consent resource [id=$id]: ${e.message}"); logger.debug(e); None }
        )

    fun readSpecimen(id: String): Option<Specimen> =
        (read(id, "Specimen") as Result<Specimen>).fold(
            { s -> Some(s) },
            { e -> logger.warn("Failed to retrieve Specimen resource [id=$id]: ${e.message}"); logger.debug(e); None }
        )

    companion object
    {
        private val logger: Logger = LogManager.getLogger(CentraXXFhirService::class.java)
    }
}