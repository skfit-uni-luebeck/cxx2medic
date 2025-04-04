package de.uksh.medic.cxx2medic.integration.service.resilience

sealed class ResilienceError(message: String, cause: Throwable? = null): Error(message, cause)

sealed class RecoveryError(message: String, cause: Throwable? = null): ResilienceError(message, cause)
{
    data class Unrecoverable(
        override val message: String = "Recovery not possible", override val cause: Throwable? = null
    ): RecoveryError(message, cause)
}

sealed class PersistenceError(message: String, cause: Throwable? = null): Error(message, cause)
{
    class FailedToStore(
        dataRep: String, cause: Throwable? = null
    ): PersistenceError("Failed to store data $dataRep", cause)
}