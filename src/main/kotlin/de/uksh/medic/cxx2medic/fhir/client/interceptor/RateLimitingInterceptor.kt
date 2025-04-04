package de.uksh.medic.cxx2medic.fhir.client.interceptor

import ca.uhn.fhir.interceptor.api.Hook
import ca.uhn.fhir.interceptor.api.Pointcut
import ca.uhn.fhir.rest.client.api.IHttpRequest
import ca.uhn.fhir.rest.client.api.IRestfulClient
import ca.uhn.fhir.util.TimeoutException
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.*

class RateLimitingInterceptor(
    private val rateLimiter: RateLimiter
) {
    @Hook(Pointcut.CLIENT_REQUEST)
    fun intercept(request: IHttpRequest, client: IRestfulClient)
    {
        logger.trace("Acquiring rate limited permission [rateLimiter=${rateLimiter.name}]")
        if (!rateLimiter.acquirePermission()) throw TimeoutException("Exceeded timeout period while waiting for rate " +
                "limited permission [rateLimiter=${rateLimiter.name}]")
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(RateLimitingInterceptor::class.java)
    }
}

fun RateLimitingInterceptor(
    rateLimiterConfig: RateLimiterConfig
) = RateLimitingInterceptor(
    RateLimiter.of("${RateLimitingInterceptor::class.qualifiedName}#${UUID.randomUUID()}", rateLimiterConfig)
)