package org.medic.cxx2medic.evaluation

object Reducers
{

    // Using short-circuiting function CollectionsKt::all instead of Boolean::and yield performance benefits for very
    // large number of elements in list but is insignificant in most cases
    val AND: List<Boolean>.() -> Boolean = { this.all { it } }

    // Using short-circuiting function CollectionsKt::any instead of Boolean::and yield performance benefits for very
    // large number of elements in list but is insignificant in most cases
    val OR: List<Boolean>.() -> Boolean = { this.any { it } }

}