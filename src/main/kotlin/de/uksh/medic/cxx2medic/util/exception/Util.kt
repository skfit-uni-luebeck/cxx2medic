package de.uksh.medic.cxx2medic.util.exception

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import kotlin.reflect.KClass

operator fun Throwable.contains(type: KClass<out Throwable>): Boolean =
    this::class == type || (cause != null && type in cause!!)

infix fun Throwable.isOrCausedBy(type: KClass<out Throwable>): Boolean =
    contains(type)

@Suppress("UNCHECKED_CAST")
fun <T: Throwable> Throwable.find(type: KClass<out T>): Option<T> =
    if (this::class == type) Some(this) as Option<T>
    else {
        if (this.cause == null) None
        else this.cause!!.find(type)
    }

