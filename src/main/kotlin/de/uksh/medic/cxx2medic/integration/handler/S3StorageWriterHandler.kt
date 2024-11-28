package de.uksh.medic.cxx2medic.integration.handler

import de.uksh.medic.cxx2medic.integration.service.S3StorageService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.MessageHeaders
import java.util.*

// TODO: Try out ServiceActivatingHandler as base class
class S3StorageWriterHandler(
    private val service: S3StorageService,
    val defaultBucket: String = "bucket",
    val defaultContentType: String = "application/json"
): MessageHandler
{
    override fun handleMessage(message: Message<*>) {
        val objectName = if (OBJECT_NAME_HEADER in message.headers) message.headers[OBJECT_NAME_HEADER] as String
                         else "object_${UUID.randomUUID()}"
        val bucketName = if (BUCKET_NAME_HEADER in message.headers) message.headers[BUCKET_NAME_HEADER] as String
                         else defaultBucket
        val (stream, contentType) = when (val payload = message.payload) {
            String -> Pair(
                (payload as String).byteInputStream(Charsets.UTF_8),
                message.headers.getOrDefault(MessageHeaders.CONTENT_TYPE, defaultContentType) as String
            )
            else -> Pair(Json.encodeToString(payload).byteInputStream(Charsets.UTF_8), "application/json")
        }
        service.uploadFile(bucketName, objectName, stream, contentType)
            .onFailure { e -> logger.error("Failed to store message in S3 storage", e) }
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(S3StorageWriterHandler::class.java)

        const val OBJECT_NAME_HEADER: String = "s3ObjectName"
        const val BUCKET_NAME_HEADER: String = "s3BucketName"
    }
}