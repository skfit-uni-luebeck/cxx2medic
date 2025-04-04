package de.uksh.medic.cxx2medic.config

import arrow.core.*
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.CacheManagementService
import de.uksh.medic.cxx2medic.integration.service.resilience.RecoveryPersistenceService
import de.uksh.medic.cxx2medic.integration.service.resilience.lastSuccessfulCompletionIntervalStart
import de.uksh.medic.cxx2medic.integration.status.RunStatus
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.config.EnableIntegration
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private typealias RecoveryData = RecoveryPersistenceService.RecoveryData

@Configuration
@EnableIntegration
class GlobalCronTriggerConfiguration(
    settings: ScheduleSettings,
    private val cacheManagerService: CacheManagementService,
    private val recoveryService: Option<RecoveryPersistenceService>
)
{
    private val triggerContext: UpToDateTriggerContext =
        recoveryService.onNone { logger.warn("Recovery is disabled. No backup data will be written") }
            .flatMap { it.get(RecoveryData.lastSuccessfulCompletionIntervalStart).fold(
                { o -> o.map { value ->
                    logger.info("Detected recovery timestamp [${formatter.format(value)}]")
                    UpToDateTriggerContext(value, value, value)
                }},
                { t ->
                    logger.warn("Failed to acquire 'lastsSuccessfulCompletionIntervalStart' timestamp", t)
                    None
                }
            ) }
            .getOrElse {
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
            }

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
            recoveryService.onSome { service ->
                // FIXME: Write Trigger and TriggerContext that actually provide a suitable timestamp for
                //        "last successfully completed execution interval start". 'lastActualExecution' only serves as
                //        an approximation
                val lastActualExecution = triggerContext.lastActualExecution()
                if (lastActualExecution != null && RunStatus.completedSuccessfully) {
                    logger.info("Updating recovery start timestamp")
                    service.update {
                        RecoveryData.lastSuccessfulCompletionIntervalStart set Some(lastActualExecution)
                    }.onFailure { throw RuntimeException("Failed to persist recovery data", it) }
                }
            }

            logger.info("Resetting run status")
            RunStatus.completedSuccessfully = true
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