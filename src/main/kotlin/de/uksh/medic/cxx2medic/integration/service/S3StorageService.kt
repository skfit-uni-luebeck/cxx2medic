package de.uksh.medic.cxx2medic.integration.service

import arrow.core.*
import arrow.resilience.retryEither
import de.uksh.medic.cxx2medic.config.ResilienceSettings
import de.uksh.medic.cxx2medic.config.S3Settings
import de.uksh.medic.cxx2medic.exception.BucketCreationException
import de.uksh.medic.cxx2medic.exception.ObjectStoringException
import de.uksh.medic.cxx2medic.util.functional.toResult
import io.github.resilience4j.ratelimiter.RateLimiter
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.apache.http.entity.ContentType
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.ConnectException

interface S3StorageService
{
    suspend fun uploadFile(bucketName: String, objectName: String, stream: InputStream, contentType: ContentType): Result<Unit>
}

@Service
class MinioStorageService(
    @Autowired private val client: MinioClient,
    @Autowired settings: S3Settings,
): S3StorageService
{
    private val retrySchedule = settings.resilience.retry.schedule(ConnectException::class).log { t, _ ->
        logger.warn("Retrying request. Reason: $t")
    }

    override suspend fun uploadFile(
        bucketName: String, objectName: String, stream: InputStream, contentType: ContentType
    ): Result<Unit> =
        retrySchedule.retryEither {
            // Check if bucket exists already
            Either.catch { client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build()) }
        }.flatMap { found ->
            if (!found) {
                // If not attempt its creation
                logger.info("Attempting to create bucket '${bucketName}' since it does not exist yet")
                retrySchedule.retryEither {
                    Either.catch { client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build()) }
                }.fold(
                    { t -> BucketCreationException(bucketName, t).left() },
                    { logger.info("Created bucket '${bucketName}' successfully"); Unit.right() }
                )
            } else Unit.right()
        }.flatMap {
            retrySchedule.retryEither {
                // Attempt to upload object
                Either.catch { client.putObject(PutObjectArgs.builder().bucket(bucketName).`object`(objectName).stream(
                    stream, stream.available().toLong(), -1
                ).contentType(contentType.toString()).build()) }
            }.fold(
                { ObjectStoringException(objectName, bucketName, it).left() },
                { o ->
                    logger.info("Uploaded object '${o.`object`()}' to bucket '${o.bucket()}' " +
                            "[eTag=${o.etag()}, versionId=${o.versionId()}]")
                    logger.debug("Response headers: [${o.headers().joinToString { it.toString() }}]")
                    Unit.right()
                }
            )
        }.toResult()

    companion object
    {
        private val logger: Logger = LogManager.getLogger(MinioStorageService::class.java)
    }
}