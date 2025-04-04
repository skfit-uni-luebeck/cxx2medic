package de.uksh.medic.cxx2medic.integration.service.resilience

import arrow.core.Either
import arrow.core.flatMap
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import de.uksh.medic.cxx2medic.config.ReplaySettings
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.integration.support.MessageBuilder
import org.springframework.messaging.Message
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class FileReplayService(
    @Autowired settings: ReplaySettings
): ReplayService<FileReplayService.Entry> {
    private val persistence: ResilientPersistenceManager<List<Entry>> = ResilientPersistenceManager(
        settings.dataDir.resolve("replay").resolve("data.json"),
        { data -> MAPPER.writeValueAsString(data) },
        { str -> MAPPER.readValue<List<Entry>>(str) }
    )
    val enabled: Boolean = settings.enabled

    override fun <T> replay(message: Message<List<T>>, acc: (List<T>, Entry) -> List<T>): Message<List<T>>
    {
        return if (!enabled) {
            logger.info("Skipping replay since it is not enabled")
            message
        } else if (persistence.isEmpty()) {
            logger.info("Skipping replay of failed messages since none were recorded")
            message
        } else {
            logger.info("Replaying failed messages")
            persistence.readData().flatMap { data -> Either.catch {
                logger.debug("Reintegrating {} failed messages into flow for reprocessing", data.size)
                val outputMsgs = data.fold(message.payload.toMutableList(), acc)
                logger.debug(
                    "Message reintegration statistics: [#replayMsgs={}, #inputMsgs={}, #outputMsgs={}]",
                    data.size, message.payload.size, outputMsgs.size
                )
                outputMsgs
            } }.onRight {
                logger.debug("Emptying replay data")
                persistence.writeData(emptyList()).onLeft { t ->
                    logger.warn("Failed to empty replay data", t)
                }
            }.fold(
                { t ->
                    logger.error("Failed to reintegrate failed messages for reprocessing => Reverting to " +
                            "updated records only", t)
                    message
                },
                { payload ->
                    logger.info("Successfully reintegrated previously failed messages into flow")
                    MessageBuilder.withPayload(payload).copyHeaders(message.headers).build()
                }
            )
        }
    }

    override fun store(entries: List<Entry>): Either<Throwable, Unit> =
        // FIXME: Probably inefficient for large lists
        persistence.updateData { it.toMutableList().apply { addAll(entries) } }.mapLeft { t ->
            logger.error("Failed to persist replay entries", t)
            PersistenceError.FailedToStore("[#entries=${entries.size}]")
        }

    override fun store(block: () -> List<Entry>): Either<Throwable, Unit> =
        store(block())

    data class Entry(
        val data: Map<String, String?>,
        val stacktrace: String,
        val timestamp: LocalDateTime
    )

    companion object
    {
        private val logger: Logger = LogManager.getLogger(FileReplayService::class.java)

        private val MAPPER = ObjectMapper().apply {
            configure(SerializationFeature.INDENT_OUTPUT, true)
            registerModules(
                KotlinModule.Builder().build(),
                JavaTimeModule()
            )
        }
    }
}