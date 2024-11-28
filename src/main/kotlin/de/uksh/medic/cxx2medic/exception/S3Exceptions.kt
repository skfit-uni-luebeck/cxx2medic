package de.uksh.medic.cxx2medic.exception

open class S3Exception(msg: String, e: Throwable? = null): Exception(msg, e)

open class BucketCreationException(bucketName: String, e: Throwable? = null):
    S3Exception("Failed to create bucket '$bucketName'", e)

open class ObjectStoringException(objectName: String, bucketName: String, e: Throwable? = null):
    S3Exception("Failed to put object '${objectName}' into bucket '${bucketName}'", e)