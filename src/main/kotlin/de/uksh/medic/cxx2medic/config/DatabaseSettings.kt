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
    val truststore: Option<TruststoreSettings> = None
)

enum class DatabaseType(
    val protocol: String,
    val driverClassName: String
)
{
    SQLSERVER("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    POSTGRESQL("postgresql", "org.postgresql.Driver");
}

fun getProtocolPart(type: DatabaseType): String =
    type.protocol