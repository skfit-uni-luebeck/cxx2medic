package de.uksh.medic.cxx2medic.integration.service

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.resilience.Schedule
import arrow.resilience.retryEither
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
import de.uksh.medic.cxx2medic.fhir.client.interceptor.RateLimitingInterceptor
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
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import kotlin.math.log
import kotlin.reflect.full.companionObject

@Service
class CentraXXFhirService(
    @Autowired private val fhirContext: FhirContext,
    @Autowired @Qualifier("cxx:fhir-settings") settings: FhirSettings
)
{
    private val url: String = settings.url
    private val client: IGenericClient = fhirContext.newRestfulGenericClient(url).also {
        settings.resilience.rateLimit.onSome { rl ->
            logger.info("CentraXX FHIR service will be rate limited [limitPerPeriod=${rl.limitForPeriod}, " +
                    "refreshPeriod=<${rl.refreshPeriod}>, timeoutDuration=<${rl.timeoutDuration}>]")
            it.registerInterceptor(RateLimitingInterceptor(rl.rateLimiter(
                "${this::class.qualifiedName}#${this.hashCode()}"
            )))
        }
    }
    private val retrySchedule = settings.resilience.retry.schedule(ConnectException::class).log { t, _ ->
        logger.warn("Retrying request. Reason: $t")
    }

    init
    {
        settings.authorization.onSome {
            it.basic.fold (
                { it.oauth.fold(
                    { throw AuthenticationException("No Authentication data provided in authentication config") },
                    { throw NotImplementedError("OAuth authentication is currently not supported") }
                ) },
                { basic -> client.registerInterceptor(BasicAuthInterceptor(basic.userName, basic.password)) }
            )
        }
    }

    suspend fun <T: IBaseResource> read(id: String, clazz: Class<T>): Result<Option<T>> =
        retrySchedule.retryEither {
            Either.catch { client.read().resource(clazz).withId(id).execute() }
        }.fold(
            { e ->
                val msg = "Failed to retrieve ${clazz.simpleName} resource [id=$id]: ${e.message}"
                when (e) {
                    is ResourceNotFoundException -> {
                        logger.debug(msg)
                        Result.success(None)
                    }
                    else -> {
                        logger.warn(msg)
                        Result.failure(e)
                    }
                }
            },
            { p -> Result.success(Some(p)) }
        )

    suspend fun read(id: String, type: String): Result<Option<IBaseResource>> =
        retrySchedule.retryEither {
           Either.catch { client.read().resource(type).withId(id).execute() }
        }.fold(
            { e ->
                val msg = "Failed to retrieve $type resource [id=$id]: ${e.message}"
                when (e) {
                    is ResourceNotFoundException -> {
                        logger.debug(msg)
                        Result.success(None)
                    }
                    else -> {
                        logger.warn(msg)
                        Result.failure(e)
                    }
                }
            },
            { p -> Result.success(Some(p)) }
        )

    @Cacheable("fhirPatientCache", cacheManager = "cacheManager")
    suspend fun readPatient(id: String): Option<Patient> =
        read(id, "Patient") as Option<Patient>

    // Caches for Consent resources are currently deactivated since they are checked for their policies and changes to
    // them are crucial to detect. Consequently, those resources should be kept up to date. Alternatively one could
    // reset the cache before each run to at least cache resources within a single run
    @Cacheable("fhirConsentCache", cacheManager = "cacheManager")
    suspend fun readConsent(id: String): Option<Consent> =
        read(id, "Consent") as Option<Consent>

    suspend fun readSpecimen(id: String): Option<Specimen> =
        read(id, "Specimen") as Option<Specimen>

    companion object
    {
        private val logger: Logger = LogManager.getLogger(CentraXXFhirService::class.java)
    }
}