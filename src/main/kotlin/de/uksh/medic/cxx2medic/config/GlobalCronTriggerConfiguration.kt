package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Some
import arrow.core.flatMap
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.CacheManagementService
import de.uksh.medic.cxx2medic.integration.service.RecoveryPersistenceService
import de.uksh.medic.cxx2medic.integration.service.lastSuccessfulCompletionIntervalStart
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.config.EnableIntegration
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.log

private typealias RecoveryData = RecoveryPersistenceService.RecoveryData

@Configuration
@EnableIntegration
class GlobalCronTriggerConfiguration(
    settings: ScheduleSettings,
    private val cacheManagerService: CacheManagementService,
    private val recoveryService: RecoveryPersistenceService
)
{
    private val triggerContext: UpToDateTriggerContext =
        recoveryService.get(RecoveryData.lastSuccessfulCompletionIntervalStart).getOrThrow().fold(
            {
                when (val ts = settings.catchupFrom) {
                    is None -> {
                        val value = Instant.now()
                        logger.info("Starting from current timestamp [${formatter.format(value)}]")
                        UpToDateTriggerContext(value, value, value)
                    }
                    is Some -> {
                        val value = ts.value
                        logger.info("Starting from catchup timestamp [${formatter.format(value)}]")
                        UpToDateTriggerContext(value, value, value)
                    }
                }
            },
            { value ->
                logger.info("Detected recovery timestamp [${formatter.format(value)}]")
                UpToDateTriggerContext(value, value, value)
            }
        )

    private val trigger: CronTrigger = object: CronTrigger(settings.cron)
    {
        override fun nextExecution(triggerContext: TriggerContext): Instant
        {
            // Clear caches
            if (triggerContext.lastActualExecution() != null) {
                cacheManagerService.clearAllCaches()
            }
            // Call the superclass to get the next execution time
            val nextExecution = super.nextExecution(triggerContext)
            // Capture the current trigger context
            this@GlobalCronTriggerConfiguration.triggerContext.update(
                triggerContext.lastScheduledExecution() ?: this@GlobalCronTriggerConfiguration.triggerContext.lastScheduledExecution(),
                triggerContext.lastActualExecution() ?: this@GlobalCronTriggerConfiguration.triggerContext.lastActualExecution(),
                triggerContext.lastCompletion() ?: this@GlobalCronTriggerConfiguration.triggerContext.lastCompletion(),
                nextExecution
            )
            // Update recovery data
            val lastCompletion = triggerContext.lastCompletion()
            if (lastCompletion != null) {
                logger.info("Updating recovery start timestamp")
                recoveryService.update {
                    RecoveryData.lastSuccessfulCompletionIntervalStart set Some(lastCompletion)
                }.onFailure { throw RuntimeException("Failed to persist recovery data", it) }
            }
            return nextExecution
        }
    }

    init
    {
        logger.info("Initialized global cron trigger [cron=${settings.cron}]")
    }

    @Bean("global:trigger-ctx")
    fun triggerContext() = triggerContext

    @Bean("global:trigger")
    fun trigger() = trigger

    companion object
    {
        private val logger: Logger = LogManager.getLogger(GlobalCronTriggerConfiguration::class.java)
        private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
    }
}