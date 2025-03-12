package de.uksh.medic.cxx2medic.config

import arrow.core.None
import arrow.core.Option
import java.beans.PropertyEditorSupport

data class DatabaseSettings(
    val type: DatabaseType,
    val host: String,
    val port: Int,
    val name: String,
    val username: String,
    val password: String,
    val truststore: Option<TruststoreSettings> = None,
    val parameters: Map<String, String> = emptyMap()
) {
    val connectionUrl: String by lazy { when(type) {
        DatabaseType.SQLSERVER -> {
            "jdbc:${type.protocol}://$host:$port;databaseName=$name" +
                    if (parameters.isNotEmpty())
                        ";" + parameters.map { e -> "${e.key}=${e.value}" }.joinToString(";")
                    else ""
        }
        DatabaseType.POSTGRESQL -> {
            "jdbc:${type.protocol}://$host:$port/$name" +
                    if (parameters.isNotEmpty())
                        "?" + parameters.map { e -> "${e.key}=${e.value}" }.joinToString("&")
                    else ""
        }
    } }
}

enum class DatabaseType(
    val protocol: String,
    val driverClassName: String
)
{
    SQLSERVER("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    POSTGRESQL("postgresql", "org.postgresql.Driver");
}