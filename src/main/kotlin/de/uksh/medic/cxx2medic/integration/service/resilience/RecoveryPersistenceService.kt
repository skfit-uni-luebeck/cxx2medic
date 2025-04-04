package de.uksh.medic.cxx2medic.integration.service.resilience

import arrow.core.*
import arrow.integrations.jackson.module.*
import arrow.optics.Copy
import arrow.optics.optics
import com.fasterxml.jackson.databind.ObjectMapper
import de.uksh.medic.cxx2medic.config.RecoverySettings
import org.apache.logging.log4j.LogManager
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant
import arrow.optics.Lens
import arrow.optics.copy
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import de.uksh.medic.cxx2medic.util.functional.toResult
import java.util.concurrent.locks.ReentrantReadWriteLock

@Service
class RecoveryPersistenceService(
    settings: RecoverySettings
) {
    private val persistence = ResilientPersistenceManager(
        settings.dataDir.resolve("recovery").resolve("recovery.json"),
        { data -> MAPPER.writeValueAsString(data) },
        { str -> MAPPER.readValue<RecoveryData>(str) }
    )
    private lateinit var prevRecoveryFileModified: Instant
    private lateinit var recoveryData: RecoveryData

    init
    {
        persistence.writeData(RecoveryData())
    }

    fun <T: Any?> get(attribute: Lens<RecoveryData, T>): Result<T> =
        getRecoveryData().map { attribute.get(it) }.fold(
            { value -> Result.success(value) },
            { exc -> Result.failure(Exception("Failed to read recovery data", exc)) }
        )

    fun update(f: Copy<RecoveryData>.() -> Unit): Result<Unit> =
        getRecoveryData().flatMap { persistence.writeData(it.copy(f)).toResult() }.fold(
            { unit -> Result.success(unit) },
            { exc -> Result.failure(Exception("Failed to update recovery data", exc)) }
        )

    fun getRecoveryData(): Result<RecoveryData>
    {
        val lastModified = persistence.lastModified()
        if (!this::prevRecoveryFileModified.isInitialized) prevRecoveryFileModified = lastModified
        return if (lastModified != prevRecoveryFileModified || !this::recoveryData.isInitialized) {
            persistence.readData().mapLeft { t ->
                Exception("Failed to load recovery data", t)
            }.toResult()
        } else Result.success(recoveryData)
    }

    companion object
    {
        private val logger = LogManager.getLogger(RecoveryPersistenceService::class)

        private val MAPPER = ObjectMapper().apply {
            configure(SerializationFeature.INDENT_OUTPUT, true)
            registerModules(
                KotlinModule.Builder().build(),
                OptionModule,
                JavaTimeModule()
            )
        }
    }

    @optics data class RecoveryData(
        val lastSuccessfulCompletionIntervalStart: Option<Instant> = None
    ) {
        companion object
    }
}