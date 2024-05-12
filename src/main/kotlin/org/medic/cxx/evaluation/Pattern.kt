package org.medic.cxx.evaluation

class Pattern<TARGET: Any>: Evaluable<TARGET>
{

    private val conditions = mutableListOf<Pair<TARGET.() -> Any, Evaluable<Any>>>()

    fun <TYPE: Any> path(path: TARGET.() -> TYPE, initializer: Pattern<TYPE>.() -> Unit)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Pair(path, Pattern<TYPE>().apply(initializer) as Evaluable<Any>))
        return
    }

    fun check(condition: TARGET.() -> Boolean) {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Pair(Functions::identity, Evaluable<TARGET> { target -> target.let(condition) })
                as Pair<TARGET.() -> Any, Evaluable<Any>>
        )
    }

    override fun evaluate(target: TARGET): Boolean =
        this.conditions.map { it.second.evaluate(it.first(target)) }.reduce(Boolean::and)

}

fun <TARGET: Any> pattern(initializer: Pattern<TARGET>.() -> Unit): Pattern<TARGET> =
    Pattern<TARGET>().apply(initializer)