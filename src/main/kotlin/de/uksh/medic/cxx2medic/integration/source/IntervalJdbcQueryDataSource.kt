package de.uksh.medic.cxx2medic.integration.source

import arrow.core.Either
import arrow.resilience.Schedule
import arrow.resilience.retryEither
import de.uksh.medic.cxx2medic.integration.scheduling.IntervalProvider
import de.uksh.medic.cxx2medic.integration.status.RunStatus
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.integration.core.MessageSource
import org.springframework.integration.support.MessageBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.messaging.Message
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

class IntervalJdbcQueryDataSource(
    queryTemplate: String,
    dataSource: DataSource,
    private val intervalProvider: IntervalProvider,
    private val retrySchedule: Schedule<Throwable, Any>
): MessageSource<List<Map<String, String?>>>
{
    private val template: JdbcTemplate = JdbcTemplate().apply { this.dataSource = dataSource }
    private val psc = PreparedStatementCreator { c: Connection -> c.prepareStatement(queryTemplate) }
    private val pss = PreparedStatementSetter { stmt: PreparedStatement ->
        val (start, end) = intervalProvider.getInterval().run { start to end }
        stmt.setTimestamp(1, Timestamp.from(start))
        stmt.setTimestamp(2, Timestamp.from(end))
    }
    private val rse = ResultSetExtractor<List<Map<String, String?>>> { rs: ResultSet ->
        val metadata = rs.metaData
        val results = mutableListOf<Map<String, String>>()
        var size = 0L
        while (rs.next()) {
            results.add((1..metadata.columnCount).associate { i ->
                metadata.getColumnName(i).lowercase() to rs.getString(i)
            })
            size++
        }
        rs.close()
        val statistics = results.groupBy { e -> e["change_kind"] }.map { e -> e.key to e.value.size }
        logger.info("Received $size records [${statistics.joinToString { "${it.first}: ${it.second}" }}]")
        results
    }

    override fun receive(): Message<List<Map<String, String?>>>?
    {
        val (start, end) = intervalProvider.getInterval().run { start to end }
        logger.info("Querying database for updated records within [{}, {})", start, end)
        val payload = runBlocking {
            retrySchedule.log { t, _ -> logger.warn("Retrying database query. Reason: $t") }
                .retryEither { Either.catch { template.query(psc, pss, rse) } }
        }.fold(
            { t ->
                logger.error("Failed to load data from database source. No records will be processed", t)
                RunStatus.completedSuccessfully = false
                emptyList()
            },
            { it }
        )
        return MessageBuilder.withPayload(payload).build()
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(IntervalJdbcQueryDataSource::class.java)
    }
}