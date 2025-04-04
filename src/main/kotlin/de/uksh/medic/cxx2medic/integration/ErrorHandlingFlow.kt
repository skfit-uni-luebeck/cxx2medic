package de.uksh.medic.cxx2medic.integration

import de.uksh.medic.cxx2medic.exception.ConnectionException
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.dsl.integrationFlow
import org.springframework.integration.router.ErrorMessageExceptionTypeRouter
import org.springframework.messaging.support.ErrorMessage

//@Configuration
//@EnableIntegration
class ErrorHandlingFlow
{
    //@Bean
    fun routeErrorMessage() = integrationFlow("errorChannel") {
        routeByException {
            channelMapping(ConnectionException::class.java, "connectionErrorChannel")
            defaultOutputChannel("logError")
        }
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(ErrorHandlingFlow::class.java);

        const val INPUT_CHANNEL_NAME = "errorChannel"
    }
}