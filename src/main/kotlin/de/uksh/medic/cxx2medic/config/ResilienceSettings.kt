package de.uksh.medic.cxx2medic.config

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.resilience.Schedule
import de.uksh.medic.cxx2medic.exception.ConnectionException
import de.uksh.medic.cxx2medic.util.exception.contains
import de.uksh.medic.cxx2medic.util.exception.isOrCausedBy
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import java.net.ConnectException
import kotlin.math.max
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class ResilienceSettings(
    rateLimit: RateLimiterSettings? = null,
    retry: RetrySettings? = null
) {
    val rateLimit: Option<RateLimiterSettings> = if (rateLimit == null) None else Some(rateLimit)
    val retry: RetrySettings = retry ?: RetrySettings()
}

class RateLimiterSettings(
    val limitForPeriod: Int,
    refreshPeriod: String,
    timeoutDuration: String?
) {
    val refreshPeriod = Duration.parse(refreshPeriod)
    val timeoutDuration = if (timeoutDuration == null) Duration.INFINITE else Duration.parse(timeoutDuration)

    fun rateLimiter(name: String): RateLimiter = RateLimiter.of(
        name,
        RateLimiterConfig.custom()
            .limitForPeriod(limitForPeriod)
            .limitRefreshPeriod(refreshPeriod.toJavaDuration())
            .timeoutDuration(timeoutDuration.toJavaDuration())
            .build()
    )
}

class RetrySettings(
    exponential: String = "0s",
    maxInterval: String = "0s",
    val limit: Long = Long.MAX_VALUE
) {
    val exponential: Duration = Duration.parse(exponential)
    val maxInterval: Duration = Duration.parse(maxInterval)

    fun schedule(causes: Set<KClass<out Throwable>>) =
        if (exponential == Duration.ZERO || maxInterval == Duration.ZERO) Schedule.recurs<Throwable>(0)
        else (Schedule.exponential<Throwable>(exponential)
            .doWhile { _, d -> d < maxInterval }
            .andThen(
                Schedule.spaced<Throwable>(maxInterval) and
                        (if (limit < Long.MAX_VALUE) Schedule.recurs(limit) else Schedule.forever())
            )
            .doWhile { t, _ -> causes.any { t isOrCausedBy it } })

    fun schedule(vararg causes: KClass<out Throwable>) =
        schedule(if (causes.isNotEmpty()) causes.toSet() else setOf(ConnectException::class))
}
