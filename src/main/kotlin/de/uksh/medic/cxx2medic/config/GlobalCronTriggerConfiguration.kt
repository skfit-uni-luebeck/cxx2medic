package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Some
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
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


@Configuration
@EnableIntegration
class GlobalCronTriggerConfiguration(
    settings: ScheduleSettings
)
{
    private val triggerContext: UpToDateTriggerContext = when (val ts = settings.catchupFrom) {
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
    private val trigger: CronTrigger = object: CronTrigger(settings.cron)
    {
        override fun nextExecution(triggerContext: TriggerContext): Instant
        {
            // Call the superclass to get the next execution time
            val nextExecution = super.nextExecution(triggerContext)
            // Capture the current trigger context
            this@GlobalCronTriggerConfiguration.triggerContext.update(
                triggerContext.lastScheduledExecution() ?: this@GlobalCronTriggerConfiguration.triggerContext.lastScheduledExecution(),
                triggerContext.lastActualExecution() ?: this@GlobalCronTriggerConfiguration.triggerContext.lastActualExecution(),
                triggerContext.lastCompletion() ?: this@GlobalCronTriggerConfiguration.triggerContext.lastCompletion(),
                nextExecution
            )
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