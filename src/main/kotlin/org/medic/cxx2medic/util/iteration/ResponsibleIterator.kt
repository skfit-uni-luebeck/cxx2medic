package org.medic.cxx2medic.util.iteration

import java.io.Closeable

abstract class ResponsibleIterator<T, I>(private val iterable: I): Iterator<T> where I: Iterable<T>, I: Finite, I: Closeable
{

    override fun hasNext(): Boolean
    {
        val hasEnded = this.iterable.hasEnded()
        if (hasEnded) iterable.close()
        return !hasEnded
    }

    abstract override fun next(): T

    fun close() = this.iterable.close()

}