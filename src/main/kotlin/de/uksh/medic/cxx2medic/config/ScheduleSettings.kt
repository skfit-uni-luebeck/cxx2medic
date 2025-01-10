package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@ConfigurationProperties("schedule")
class ScheduleSettings(
    val cron: String = "0 * * * * *",
    catchupFrom: String? = null
) {
    val catchupFrom: Option<Instant> =
        if (catchupFrom == null) None
        else Some(Instant.from(LocalDateTime.parse(catchupFrom, formatter).atZone(ZoneId.systemDefault())))

    companion object
    {
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}