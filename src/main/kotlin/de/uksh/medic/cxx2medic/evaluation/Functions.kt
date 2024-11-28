package de.uksh.medic.cxx2medic.evaluation

object Functions
{

    inline fun identity(x: Any) = x

    inline fun <TYPE1, TYPE2> path(x: TYPE1, f: TYPE1.() -> TYPE2): TYPE2 = f(x)

}