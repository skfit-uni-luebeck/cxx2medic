package de.uksh.medic.cxx2medic.integration.service.resilience

import arrow.core.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name

class ResilientPersistenceManager<T>(
    filePath: Path,
    private val serializer: (T) -> String,
    private val deserializer: (String) -> T
) {
    private val file: File = filePath.toFile()
    private val backupFile: File = filePath.parent.resolve("${filePath.name}.backup").toFile()
    
    init
    {
        filePath.createParentDirectories()
    }

    fun readData(): Either<Throwable, T>
    {
        logger.debug("Reading data")
        return lockingRead(file).flatMap { Either.catch { deserializer(it) } }.recover { t ->
                logger.warn(
                    "Failed to read data from main file @ {}. Attempting to read backup data @ {}. Reason: {}",
                    file, backupFile, t
                )
                lockingRead(backupFile).flatMap { Either.catch { deserializer(it) } }.mapLeft {
                    RecoveryError.Unrecoverable("Failed to read data from backup file @ $backupFile", it)
                }.bind()
            }
    }

    fun writeData(data: T): Either<Throwable, Unit>
    {
        logger.debug("Writing data")
        return getLock(file).write { getLock(backupFile).write {
            Either.catch { serializer(data) }.flatMap { str ->
                logger.debug("Writing data to main file @ {}", file)
                Either.catch { file.writeText(str, Charsets.UTF_8) }.map { str }
            }.flatMap { str ->
                logger.debug("Writing data to backup file @ {}", backupFile)
                Either.catch { backupFile.writeText(str, Charsets.UTF_8) }
            }
        } }.mapLeft { t -> Exception("Failed to write data since it could not be persisted consistently", t) }
    }

    fun updateData(transform: (T) -> T): Either<Throwable, Unit> =
        readData().flatMap { Either.catch { transform(it) } }.flatMap { writeData(it) }

    fun <U> updateData(reducer: (T, U) -> T, data: U): Either<Throwable, Unit> =
        updateData(reducer.partially2(data))
    
    fun lastModified(): Instant = Instant.ofEpochMilli(file.lastModified())
    
    fun backupLastModified(): Instant = Instant.ofEpochMilli(backupFile.lastModified())

    fun isEmpty(): Boolean = file.length() > 0

    fun isBackupEmpty(): Boolean = backupFile.length() > 0

    private fun lockingRead(file: File): Either<Throwable, String> =
        getLock(file).read { Either.catch { file.readText(Charsets.UTF_8) } }

    private fun lockingWrite(file: File, data: String): Either<Throwable, Unit> =
        getLock(file).write { Either.catch { file.writeText(data, Charsets.UTF_8) } }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(ResilientPersistenceManager::class.java)

        private val ACCESS_MAP: MutableMap<File, ReentrantReadWriteLock> = mutableMapOf()

        private fun getLock(file: File): ReentrantReadWriteLock =
            ACCESS_MAP.getOrPut(file) { ReentrantReadWriteLock() }
    }
}