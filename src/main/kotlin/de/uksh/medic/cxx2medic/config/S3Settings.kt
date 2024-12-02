package de.uksh.medic.cxx2medic.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("s3")
data class S3Settings(
    val url: String,
    val bucketName: String,
    val access: AccessSettings,
    val bundleSizeLimit: Int = Int.MAX_VALUE
) {
    data class AccessSettings(
        val key: String,
        val secret: String
    )
}