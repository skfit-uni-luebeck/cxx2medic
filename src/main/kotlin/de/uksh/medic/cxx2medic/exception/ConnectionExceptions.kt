package de.uksh.medic.cxx2medic.exception

open class ConnectionException(msg: String, e: Throwable? = null): Exception(msg, e)

open class DatabaseConnectionException(msg: String, e: Throwable? = null): ConnectionException(msg, e)

open class FhirServerConnectionException(msg: String, e: Throwable? = null): ConnectionException(msg, e)

open class S3ConnectionException(msg: String, e: Throwable? = null): ConnectionException(msg, e)