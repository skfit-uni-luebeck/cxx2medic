package org.medic.cxx.evaluation

class EvaluationPattern<TARGET1>
{
    class ConstrainedPair<in FIRST, in SECOND>(private val first: FIRST, private val second: SECOND)

    private val criteria: MutableList<ConstrainedPair<TARGET1.(Any) -> Boolean, Any>> = mutableListOf()

    /* FIXME: I'd like to use a separate generic type VALUE constrained by Any (Value: Any) to force
       FIXME: both the function argument and the value to be of the same type but apparently the
       FIXME: contravariant parameters of the the Pair constructor prevent this (I assume as much) */
    fun <VALUE : Any> addCriterion(f: TARGET1.(VALUE) -> Boolean, v: VALUE): EvaluationPattern<TARGET1>
    {
        this.criteria.add(ConstrainedPair(f, v))
        return this
    }

    fun addCriterion(f: TARGET1.(Unit) -> Boolean): EvaluationPattern<TARGET1>
    {
        this.criteria.add(Pair(f, Unit))
        return this
    }

    fun <TARGET2> addPattern(p: EvaluationPattern<TARGET2>, target: TARGET2): EvaluationPattern<TARGET1>
    {
        this.criteria.add(Pair({ p.evaluate(target) }, Unit))
        return this
    }

    fun evaluate(target: TARGET1): Boolean =
        criteria.map { (f, v) -> target.f(v) }.reduce(Boolean::and)

}