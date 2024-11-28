package de.uksh.medic.cxx2medic.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("schedule")
data class ScheduleSettings(
    val cron: String = "0 * * * * *"
)