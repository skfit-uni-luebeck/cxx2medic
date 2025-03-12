package de.uksh.medic.cxx2medic.config

import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.RecoveryPersistenceService
import de.uksh.medic.cxx2medic.integration.source.IntervalJdbcQueryDataSource
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.core.MessageSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@Configuration
@EnableIntegration
class CentraXXDataSourceConfiguration(
    @Autowired @Qualifier("cxx:db-settings") val settings: DatabaseSettings
)
{
    private val queryTemplateStr: String = """
        WITH consent(consent_id, consent_patient_id, consent_change_kind, consent_change_date, consent_rn) AS
        (
           SELECT OID, PATIENTCONTAINER, change_kind, change_date, ROW_NUMBER() OVER (PARTITION BY OID ORDER BY change_id DESC)
           FROM CENTRAXX_CONSENT
           WHERE change_user != 'flyway'
        ),
        sample(specimen_id, patient_id, sample_consent_id, sample_change_kind, sample_change_date, sample_rn) AS
        (
           SELECT OID, PATIENTCONTAINER, CONSENT, change_kind, change_date, ROW_NUMBER() OVER (PARTITION BY OID ORDER BY change_id DESC)
           FROM CENTRAXX_SAMPLE
           WHERE change_user != 'flyway' AND DTYPE != 'ALIQUOTGROUP'
        )
        SELECT specimen_id, patient_id,  sample_consent_id AS consent_id, IIF(consent_change_date>sample_change_date, consent_change_kind, sample_change_kind) AS change_kind
        FROM sample LEFT JOIN consent ON sample_consent_id = consent_id
        WHERE sample_rn = 1 AND (consent_rn = 1 OR consent_rn is NULL)
        AND IIF(consent_change_date>sample_change_date, consent_change_date, sample_change_date) >= ?
        AND IIF(consent_change_date>sample_change_date, consent_change_date, sample_change_date) < ?
        ORDER BY patient_id, consent_id, specimen_id
    """.trimIndent()

    @Bean("cxx:db-source")
    fun dataSource(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName(settings.type.driverClassName)
            url = settings.connectionUrl
            username = settings.username
            password = settings.password
        }.also { logger.info("Initialized CentraXX database source [url=${settings.connectionUrl}, " +
                "driver=${settings.type.driverClassName}]") }

    @Bean("cxx:msg-source")
    fun messageSource(
        @Autowired @Qualifier("cxx:db-source") dataSource: DataSource,
        @Autowired @Qualifier("global:trigger-ctx") triggerContext: UpToDateTriggerContext
    ): MessageSource<List<Map<String, String?>>> =
        IntervalJdbcQueryDataSource(queryTemplateStr, dataSource, triggerContext)
            .also { logger.info("Initialized CentraXX database message source") }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(CentraXXDataSourceConfiguration::class.java)
    }
}