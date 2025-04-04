package de.uksh.medic.cxx2medic.integration.handler

import arrow.core.partially1
import de.uksh.medic.cxx2medic.integration.service.resilience.FileReplayService
import de.uksh.medic.cxx2medic.integration.service.resilience.ReplayService
import de.uksh.medic.cxx2medic.integration.service.resilience.ResilientPersistenceManager
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.support.ErrorMessage
import org.springframework.util.ErrorHandler

class ReplayingErrorHandler<E>(
    private val service: ReplayService<E>,
    private val reduce: (ErrorMessage) -> List<E>
): MessageHandler
{
    override fun handleMessage(message: Message<*>) = when (message) {
        is ErrorMessage -> handleErrorMessage(message)
        else -> {
            logger.warn("Non-error message received [messageId='${message.headers.id}', " +
                    "type=${message::class.qualifiedName}, payloadType=${message.payload::class.qualifiedName}, " +
                    "headers=${message.headers} => Ignoring")
        }
    }

    private fun handleErrorMessage(message: ErrorMessage)
    {
        logger.error(
            "Failed to process message [messageId='{}']. Persisting associated data for future retry",
            message.originalMessage.headers.id
        )
        service.store(reduce.partially1(message)).mapLeft { t ->
            logger.error(
                "Failed to persist error message data for future replay [errorMessageId='${message.headers.id}', " +
                        "messageId='${message.originalMessage.headers.id}']",
                t
            )
        }
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(ReplayingErrorHandler::class.java)
    }
}