package de.uksh.medic.cxx2medic.integration.handler

import de.uksh.medic.cxx2medic.exception.S3Exception
import de.uksh.medic.cxx2medic.exception.UnsupportedValueException
import de.uksh.medic.cxx2medic.integration.service.S3StorageService
import de.uksh.medic.cxx2medic.util.isEqualTo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.http.entity.ContentType
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.MessageHeaders
import java.io.InputStream
import java.util.*

// TODO: Try out ServiceActivatingHandler as base class
class S3StorageWriterHandler(
    private val service: S3StorageService,
    val defaultBucket: String = "bucket",
    val defaultContentType: ContentType = ContentType.APPLICATION_JSON
): MessageHandler
{
    init
    {
        if (defaultContentType !in supportedContentTypes)
            throw IllegalArgumentException("Unsupported content type '$defaultContentType'. " +
                    "Expected one of $supportedContentTypes")
    }

    override fun handleMessage(message: Message<*>) {
        val objectName = if (OBJECT_NAME_HEADER in message.headers) message.headers[OBJECT_NAME_HEADER] as String
                         else "object_${UUID.randomUUID()}"
        val bucketName = if (BUCKET_NAME_HEADER in message.headers) message.headers[BUCKET_NAME_HEADER] as String
                         else defaultBucket
        val str = (message.headers[MessageHeaders.CONTENT_TYPE] as String)
        val contentType = when {
            str.isEmpty() -> defaultContentType
            else -> ContentType.parse(str).run { if (charset == null) withCharset(Charsets.UTF_8) else this }
        }
        val stream = when (val payload = message.payload) {
            is String, is ByteArray, is InputStream -> when (payload) {
                is String -> payload.byteInputStream(contentType.charset)
                is ByteArray -> payload.inputStream()
                else -> payload as InputStream
            }
            else -> when {
                contentType.isEqualTo(ContentType.APPLICATION_JSON) ->
                    Json.encodeToString(payload).byteInputStream(contentType.charset)
                else -> throw UnsupportedValueException("Unsupported content type '$contentType' provided by message " +
                        "header 'contentType'. Expected one of $supportedContentTypes")
            }
        }
        service.uploadFile(bucketName, objectName, stream, contentType)
            .onFailure { e -> logger.error("Failed to store message in S3 storage", e) }
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(S3StorageWriterHandler::class.java)

        const val OBJECT_NAME_HEADER: String = "s3ObjectName"
        const val BUCKET_NAME_HEADER: String = "s3BucketName"

        val supportedContentTypes: Set<ContentType> = setOf(
            ContentType.APPLICATION_JSON
        )
    }
}