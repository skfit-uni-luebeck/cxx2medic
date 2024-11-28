package de.uksh.medic.cxx2medic

import ca.uhn.fhir.context.FhirContext
import de.uksh.medic.cxx2medic.config.DatabaseSettings
import de.uksh.medic.cxx2medic.config.CentraXXSettings
import de.uksh.medic.cxx2medic.config.FhirSettings
import de.uksh.medic.cxx2medic.config.S3Settings
import io.minio.MinioClient
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.concurrent.ConcurrentMapCacheFactoryBean
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean

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
    fun fhirPatientCache() =
        ConcurrentMapCacheFactoryBean().apply { setName("fhir-patient") }

    @Bean
    fun fhirConsentCache() =
        ConcurrentMapCacheFactoryBean().apply { setName("fhir-consent") }

    @Bean
    fun cacheManager(fhirPatientCache: ConcurrentMapCache, fhirConsentCache: ConcurrentMapCache) =
        SimpleCacheManager().apply { setCaches(setOf(fhirPatientCache, fhirConsentCache)) }

    @Bean("cxx:db-settings")
    fun centraxxDatabaseSettings(centraxxSettings: CentraXXSettings): DatabaseSettings =
        centraxxSettings.database

    @Bean("cxx:fhir-settings")
    fun centraxxFhirSettings(centraxxSettings: CentraXXSettings): FhirSettings =
        centraxxSettings.fhir

    @Bean
    fun minioClient(s3Settings: S3Settings): MinioClient =
        MinioClient.builder().endpoint(s3Settings.url).credentials(s3Settings.access.key, s3Settings.access.key).build()
}

fun main(args: Array<String>): Unit
{
    SpringApplication.run(CXX2S3Application::class.java, *args)
}