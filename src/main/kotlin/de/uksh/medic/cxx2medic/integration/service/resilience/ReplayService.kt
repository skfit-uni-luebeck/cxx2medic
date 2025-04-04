package de.uksh.medic.cxx2medic.integration.service.resilience

import arrow.core.Either
import de.uksh.medic.cxx2medic.integration.service.resilience.FileReplayService.Entry
import org.springframework.messaging.Message

interface ReplayService<E>
{
    fun <T> replay(message: Message<List<T>>, acc: (List<T>, Entry) -> List<T>): Message<List<T>>

    fun store(entries: List<E>): Either<Throwable, Unit>

    fun store(block: () -> List<E>): Either<Throwable, Unit>
}