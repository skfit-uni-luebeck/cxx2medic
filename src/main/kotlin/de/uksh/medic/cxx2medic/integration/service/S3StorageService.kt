package de.uksh.medic.cxx2medic.integration.service

import arrow.core.Either
import de.uksh.medic.cxx2medic.exception.BucketCreationException
import de.uksh.medic.cxx2medic.exception.ObjectStoringException
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.InputStream

interface S3StorageService
{
    fun uploadFile(bucketName: String, objectName: String, stream: InputStream, contentType: String): Result<Unit>
}

@Service
class MinioStorageService(
    @Autowired private val client: MinioClient
): S3StorageService
{
    override fun uploadFile(
        bucketName: String, objectName: String, stream: InputStream, contentType: String
    ): Result<Unit> =
        kotlin.runCatching {
            val found = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())
            if (!found) {
                logger.info("Attempting to create bucket '${bucketName}' since it does not exist yet")
                kotlin.runCatching { client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build()) }
                    .onSuccess { logger.info("Created bucket '${bucketName}' successfully") }
                    .onFailure { e -> throw BucketCreationException(bucketName, e) }
            }
            kotlin.runCatching {
                client.putObject(PutObjectArgs.builder().bucket(bucketName).`object`(objectName).stream(
                    stream, stream.available().toLong(), -1
                ).contentType(contentType).build())
            }.onFailure { e -> throw ObjectStoringException(objectName, bucketName, e) }
            logger.info("Uploaded object '$objectName' to bucket '$bucketName'")
        }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(MinioStorageService::class.java)
    }
}