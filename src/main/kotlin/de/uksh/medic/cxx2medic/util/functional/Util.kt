package de.uksh.medic.cxx2medic.util.functional

import arrow.core.Either

fun <A: Throwable, B> Either<A, B>.toResult(): Result<B> = when (this) {
    is Either.Left<A> -> Result.failure(this.value)
    is Either.Right<B> -> Result.success(this.value)
}