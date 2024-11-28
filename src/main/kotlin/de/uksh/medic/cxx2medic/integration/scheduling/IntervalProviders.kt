package de.uksh.medic.cxx2medic.integration.scheduling

import java.time.*
import java.util.TimeZone

interface IntervalProvider
{
    fun getInterval(): TimeInterval
}

interface InitiallyOpenIntervalProvider: IntervalProvider

data class TimeInterval(
    val start: Instant,
    val end: Instant
) {
    fun getDuration(): Duration =
        Duration.between(start, end)

    fun getPeriod(): Period =
        Period.between(
            LocalDate.ofInstant(start, ZoneId.systemDefault()), LocalDate.ofInstant(end, ZoneId.systemDefault())
        )
}