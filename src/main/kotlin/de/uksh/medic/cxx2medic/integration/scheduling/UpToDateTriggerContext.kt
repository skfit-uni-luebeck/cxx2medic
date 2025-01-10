package de.uksh.medic.cxx2medic.integration.scheduling

import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.*
import kotlin.time.Duration

class UpToDateTriggerContext(
    @Volatile private var lastScheduledExecution: Instant? = null,
    @Volatile private var lastActualExecution: Instant? = null,
    @Volatile private var lastCompletion: Instant? = null,
    @Volatile private var currentExecution: Instant = Instant.now()
): TriggerContext, IntervalProvider
{
    private val clock: Clock = Clock.systemDefaultZone()

    override fun lastScheduledExecution(): Instant? = lastScheduledExecution

    override fun lastActualExecution(): Instant? = lastActualExecution

    override fun lastCompletion(): Instant? = lastCompletion

    fun currentExecution(): Instant = currentExecution

    fun update(
        lastScheduledExecutionTime: Date?,
        lastActualExecutionTime: Date?,
        lastCompletionTime: Date?,
        currentExecutionTime: Date
    ) = update(
        toInstant(lastScheduledExecutionTime),
        toInstant(lastActualExecutionTime),
        toInstant(lastCompletionTime),
        toInstant(currentExecutionTime)
    )

    fun update(
        lastScheduledExecution: Instant?,
        lastActualExecution: Instant?,
        lastCompletion: Instant?,
        currentExecution: Instant
    )
    {
        this.lastScheduledExecution = lastScheduledExecution
        this.lastActualExecution = lastActualExecution
        this.lastCompletion = lastCompletion
        this.currentExecution = currentExecution
    }

    override fun getInterval(): TimeInterval =
        TimeInterval(this.lastCompletion ?: Instant.ofEpochSecond(0), this.currentExecution)

    companion object
    {
        @JvmName("nullableDate")
        fun toInstant(date: Date?): Instant? = date?.toInstant()

        fun toInstant(date: Date): Instant = date.toInstant()
    }
}