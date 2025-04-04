package de.uksh.medic.cxx2medic.util.exception

import kotlin.reflect.KClass

operator fun Throwable.contains(type: KClass<out Throwable>): Boolean =
    this::class == type || (cause != null && type in cause!!)

infix fun Throwable.isOrCausedBy(type: KClass<out Throwable>): Boolean =
    contains(type)

