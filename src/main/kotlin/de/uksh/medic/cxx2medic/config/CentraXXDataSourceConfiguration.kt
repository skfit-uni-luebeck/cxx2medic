package de.uksh.medic.cxx2medic.config

import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
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
    @Autowired @Qualifier("cxx:db-settings") settings: DatabaseSettings
)
{
    private val driverClassName = getDriverClassName(settings.type)
    private val connectionUrl: String = when (val type = settings.type)
        {
            DatabaseType.POSTGRESQL ->
                "jdbc:${getProtocolPart(settings.type)}://${settings.host}:${settings.port}/${settings.database}"
            DatabaseType.MICROSOFT_SQL_SERVER ->
                "jdbc:${getProtocolPart(settings.type)}://${settings.host}:${settings.port};databaseName=${settings.database}"
        }
    private val username: String = settings.username
    private val password: String = settings.password
    private val queryTemplateStr: String = """
        WITH sample AS
        (
           SELECT *, ROW_NUMBER() OVER (PARTITION BY OID ORDER BY change_id DESC) AS rn
           FROM centraxx_sample
           WHERE change_date >= ? AND change_date < ? AND change_user != 'flyway'
        )
        SELECT sample.OID AS specimen_id, sample.PATIENTCONTAINER AS patient_id, sample.CONSENT AS consent_id, sample.change_kind AS change_kind
        FROM sample
        WHERE rn = 1
        ORDER BY patient_id, consent_id
    """.trimIndent()

    init {
        println("conn str: ${connectionUrl}")
    }

    @Bean("cxx:db-source")
    fun dataSource(): DataSource =
        DriverManagerDataSource().apply {
            setDriverClassName(driverClassName)
            url = connectionUrl
            username = this@CentraXXDataSourceConfiguration.username
            password = this@CentraXXDataSourceConfiguration.password
        }.also { logger.info("Initialized CentraXX database source [url=$connectionUrl, driver=$driverClassName]") }

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