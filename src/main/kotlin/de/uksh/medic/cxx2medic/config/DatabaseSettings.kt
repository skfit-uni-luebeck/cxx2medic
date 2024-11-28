package de.uksh.medic.cxx2medic.config

data class DatabaseSettings(
    val type: DatabaseType,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String
)

enum class DatabaseType(
    val str: String
)
{
    MICROSOFT_SQL_SERVER("sqlserver"), POSTGRESQL("postgresql")
}

fun getDriverClassName(type: DatabaseType): String =
    when (type) {
        DatabaseType.MICROSOFT_SQL_SERVER -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        DatabaseType.POSTGRESQL -> "org.postgresql.Driver"
    }

fun getProtocolPart(type: DatabaseType): String =
    type.str