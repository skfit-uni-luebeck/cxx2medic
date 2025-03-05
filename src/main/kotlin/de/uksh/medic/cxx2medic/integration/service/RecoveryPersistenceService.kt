package de.uksh.medic.cxx2medic.integration.service

import arrow.core.*
import arrow.integrations.jackson.module.*
import arrow.optics.Copy
import arrow.optics.optics
import com.fasterxml.jackson.core.exc.StreamReadException
import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.ObjectMapper
import de.uksh.medic.cxx2medic.config.RecoverySettings
import org.apache.logging.log4j.LogManager
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.time.Instant
import arrow.optics.Lens
import arrow.optics.copy
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JSR310Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createParentDirectories

@Service
class RecoveryPersistenceService(
    settings: RecoverySettings
)
{
    private val recoveryFile: File = settings.file.also { it.createParentDirectories() }.toFile()
    private lateinit var prevRecoveryFileModified: Instant
    private lateinit var recoveryData: RecoveryData

    fun <T: Any?> get(attribute: Lens<RecoveryData, T>): Result<T> =
        getRecoveryData().map { attribute.get(it) }.fold(
            { value -> Result.success(value) },
            { exc -> Result.failure(Exception("Failed to read recovery data", exc)) }
        )

    fun update(f: Copy<RecoveryData>.() -> Unit): Result<Unit> =
        getRecoveryData().flatMap { writeRecoveryData(it.copy(f)) }.fold(
            { unit -> Result.success(unit) },
            { exc -> Result.failure(Exception("Failed to update recovery data", exc)) }
        )

    fun getRecoveryData(): Result<RecoveryData> = kotlin.runCatching {
        getLock(recoveryFile).read {
            if (!recoveryFile.exists()) return@read RecoveryData()
            val lastModified = Instant.ofEpochMilli(recoveryFile.lastModified())
            if (!this::prevRecoveryFileModified.isInitialized) prevRecoveryFileModified = lastModified
            return@read if (lastModified != prevRecoveryFileModified || !this::recoveryData.isInitialized) {
                readRecoveryFile().fold(
                    { value ->
                        logger.debug("Successfully read recovery data fom file @ ${recoveryFile.absolutePath}")
                        value
                    },
                    { exc ->
                        val msg = when (exc) {
                            is StreamReadException, is DatabindException -> "Failed to parse file content"
                            is IOException -> "Failed to load file content"
                            else -> "Unexpected error occurred while reading or parsing file content"
                        }
                        throw Exception(
                            "Failed to load recovery data from file @ ${recoveryFile.absolutePath}. " +
                                    "Reason: $msg", exc
                        )
                    }
                )
            } else recoveryData
        }
    }

    private fun readRecoveryFile(): Result<RecoveryData>
    {
        logger.debug("Reading recovery data from file @ {}", recoveryFile)
        return getLock(recoveryFile).read { runCatching { MAPPER.readValue(recoveryFile, RecoveryData::class.java) } }
    }

    private fun writeRecoveryData(data: RecoveryData): Result<Unit>
    {
        logger.debug("Writing recovery data to file @ {}", recoveryFile)
        return getLock(recoveryFile).write { runCatching { MAPPER.writeValue(recoveryFile, data) } }
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
        private val ACCESS_MAP: MutableMap<File, ReentrantReadWriteLock> = mutableMapOf()

        private fun getLock(file: File): ReentrantReadWriteLock =
            ACCESS_MAP.getOrPut(file) { ReentrantReadWriteLock() }
    }

    @optics data class RecoveryData(
        val lastSuccessfulCompletionIntervalStart: Option<Instant> = None
    ) {
        companion object
    }
}