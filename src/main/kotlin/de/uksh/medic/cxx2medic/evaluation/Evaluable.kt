package de.uksh.medic.cxx2medic.evaluation

fun interface Evaluable<TARGET>
{

    fun evaluate(target: TARGET): Boolean

}