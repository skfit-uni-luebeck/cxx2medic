package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("s3", ignoreInvalidFields = false, ignoreUnknownFields = false)
class S3Settings(
    val url: String,
    val bucketName: String,
    val access: AccessSettings,
    region: String? = null,
    val bundleSizeLimit: Int = Int.MAX_VALUE,
    val resilience: ResilienceSettings = ResilienceSettings()
) {
    val region = if (region == null) None else Some(region)

    data class AccessSettings(
        val key: String,
        val secret: String
    )
}