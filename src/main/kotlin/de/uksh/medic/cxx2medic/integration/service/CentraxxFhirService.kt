package de.uksh.medic.cxx2medic.integration.service

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.apache.ApacheHttpClient
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
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
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import usr.paulolaup.fhir.client.interceptor.oauth.ClientCredentialsOAuthInterceptor
import usr.paulolaup.fhir.client.interceptor.oauth.PasswordOAuthInterceptor
import usr.paulolaup.fhir.client.interceptor.oauth.RefreshTokenOAuthInterceptor
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.companionObject

@Service
class CentraXXFhirService(
    @Autowired private val fhirContext: FhirContext,
    @Autowired @Qualifier("cxx:fhir-settings") settings: FhirSettings
)
{
    private val url: String = settings.url
    private val client: IGenericClient = fhirContext.newRestfulGenericClient(url)

    init
    {
        // Activate OAuth authorization if settings reflect its presence
        settings.authorization.onSome {
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

    final inline fun <reified T: IBaseResource> read(id: String): Option<T> =
        read(id, T::class.java)

    fun <T: IBaseResource> read(id: String, clazz: Class<T>): Option<T> =
        kotlin.runCatching { client.read().resource(clazz).withId(id).execute() }.fold(
            { p -> Some(p) },
            { e ->
                val msg = "Failed to retrieve ${clazz.simpleName} resource [id=$id]: ${e.message}"
                if (e is ResourceNotFoundException) logger.debug(msg)
                else logger.warn(msg, e)
                None
            }
        )

    fun read(id: String, type: String): Option<IBaseResource> =
        kotlin.runCatching { client.read().resource(type).withId(id).execute() }.fold(
            { p -> Some(p) },
            { e ->
                val msg = "Failed to retrieve $type resource [id=$id]: ${e.message}"
                if (e is ResourceNotFoundException) logger.debug(msg)
                else logger.warn(msg, e)
                None
            }
        )

    @Cacheable("fhirPatientCache", cacheManager = "cacheManager")
    fun readPatient(id: String): Option<Patient> =
        read(id, "Patient") as Option<Patient>

    // Caches for Consent resources are currently deactivated since they are checked for their policies and changes to
    // them are crucial to detect. Consequently, those resources should be kept up to date. Alternatively one could
    // reset the cache before each run to at least cache resources within a single run
    @Cacheable("fhirConsentCache", cacheManager = "cacheManager")
    fun readConsent(id: String): Option<Consent> =
        read(id, "Consent") as Option<Consent>

    fun readSpecimen(id: String): Option<Specimen> =
        read(id, "Specimen") as Option<Specimen>

    companion object
    {
        private val logger: Logger = LogManager.getLogger(CentraXXFhirService::class.java)
    }
}