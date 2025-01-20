package de.uksh.medic.cxx2medic

import ca.uhn.fhir.context.FhirContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.CaffeineSpec
import de.uksh.medic.cxx2medic.config.DatabaseSettings
import de.uksh.medic.cxx2medic.config.CentraXXSettings
import de.uksh.medic.cxx2medic.config.FhirSettings
import de.uksh.medic.cxx2medic.config.S3Settings
import de.uksh.medic.cxx2medic.fhir.query.FhirQuery
import io.minio.MinioClient
import org.simpleframework.xml.util.ConcurrentCache
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.concurrent.ConcurrentMapCacheFactoryBean
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import java.nio.file.Path

@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties
@ConfigurationPropertiesScan
class CXX2S3Application
{
    @Bean
    fun fhirContext(): FhirContext =
        FhirContext.forR4()

    @Bean
    fun fhirPatientCache(): ConcurrentMapCacheFactoryBean =
        ConcurrentMapCacheFactoryBean().apply { setName("fhirPatientCache") }

    @Bean
    fun fhirConsentCache(): ConcurrentMapCacheFactoryBean =
        ConcurrentMapCacheFactoryBean().apply { setName("fhirConsentCache") }

    @Bean
    fun cacheManager(): CacheManager =
        CaffeineCacheManager().apply {
            setAsyncCacheMode(true)
            cacheNames = listOf("fhirPatientCache", "fhirConsentCache")
        }

    @Bean("cxx:db-settings")
    fun centraxxDatabaseSettings(centraxxSettings: CentraXXSettings): DatabaseSettings =
        centraxxSettings.database

    @Bean("cxx:fhir-settings")
    fun centraxxFhirSettings(centraxxSettings: CentraXXSettings): FhirSettings =
        centraxxSettings.fhir

    @Bean("fhirQuery")
    fun fhirQuery(centraxxSettings: CentraXXSettings): FhirQuery
    {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        return mapper.readValue<FhirQuery>(Path.of(centraxxSettings.criteriaFile).toFile())
    }

    @Bean
    fun minioClient(s3Settings: S3Settings): MinioClient =
        MinioClient.builder().apply {
            endpoint(s3Settings.url)
            credentials(s3Settings.access.key, s3Settings.access.secret)
            s3Settings.region.onSome { region(it) }
        }.build()

    @Bean
    fun bundleSizeLimit(s3Settings: S3Settings): Int =
        s3Settings.bundleSizeLimit

    @Bean("bucketName")
    fun bucketName(s3Settings: S3Settings): String =
        s3Settings.bucketName
}

fun main(args: Array<String>): Unit
{
    SpringApplication.run(CXX2S3Application::class.java, *args)
}