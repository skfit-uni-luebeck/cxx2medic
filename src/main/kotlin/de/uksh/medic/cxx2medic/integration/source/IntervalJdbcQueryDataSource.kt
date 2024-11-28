package de.uksh.medic.cxx2medic.integration.source

import de.uksh.medic.cxx2medic.integration.scheduling.IntervalProvider
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
    private val intervalProvider: IntervalProvider
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
                metadata.getColumnName(i) to rs.getString(i)
            })
            size++
        }
        rs.close()
        val statistics = results.groupBy { e -> e["change_type"] }.map { e -> e.key to e.value.size }
        logger.info("Received $size records [${statistics.joinToString { "${it.first}: ${it.second}" }}]")
        results
    }

    override fun receive(): Message<List<Map<String, String?>>>?
    {
        val (start, end) = intervalProvider.getInterval().run { start to end }
        logger.info("Querying database for updated records within [{}, {})", start, end)
        val payload = template.query(psc, pss, rse)
        return if (payload != null) MessageBuilder.withPayload(payload).build() else null
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(IntervalJdbcQueryDataSource::class.java)
    }
}