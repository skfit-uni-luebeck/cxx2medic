package de.uksh.medic.cxx2medic.config

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.config.EnableIntegration
import java.util.concurrent.TimeUnit

@Configuration
@EnableIntegration
class FhirContextConfiguration
{
    private val fhirContext = FhirContext.forR4().apply {
        restfulClientFactory.apply {
            serverValidationMode = ServerValidationModeEnum.ONCE
            setHttpClient(configureApacheHttpClient(this))
        }
    }

    @Bean
    fun fhirContext(): FhirContext =
        this.fhirContext

    companion object
    {
        private fun  configureApacheHttpClient(clientFactory: IRestfulClientFactory): HttpClient
        {
            val requestConfig = RequestConfig.custom()
                .setSocketTimeout(clientFactory.socketTimeout)
                .setConnectTimeout(clientFactory.socketTimeout)
                .setConnectionRequestTimeout(clientFactory.connectionRequestTimeout)
                .setStaleConnectionCheckEnabled(true)
                .build()

            val builder = HttpClients.custom()
                .useSystemProperties()
                .setDefaultRequestConfig(requestConfig)
                // Support sessions
                .setDefaultCookieStore(BasicCookieStore())

            val connManager =
                PoolingHttpClientConnectionManager(clientFactory.connectionTimeToLive.toLong(), TimeUnit.MILLISECONDS)
            connManager.maxTotal = clientFactory.poolMaxTotal
            connManager.defaultMaxPerRoute = clientFactory.poolMaxPerRoute
            builder.setConnectionManager(connManager)

            return builder.build()
        }
    }
}