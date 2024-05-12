package org.medic.cxx.evaluation

fun interface Evaluable<TARGET>
{

    fun evaluate(target: TARGET): Boolean

}