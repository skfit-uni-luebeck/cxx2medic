package de.uksh.medic.cxx2medic.evaluation

import java.lang.NullPointerException

class Pattern<TARGET: Any>(
    private val reductionFunction: (List<Boolean>) -> Boolean = Reducers.AND,
): Evaluable<TARGET>
{

    val conditions = mutableListOf<Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>>()

    inline fun <reified TYPE: Any> path(crossinline path: TARGET.() -> TYPE?, block: Pattern<TYPE>.() -> Unit)
    {
        val safePath = wrapCastSafe<TARGET, TYPE>(path, null)
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(safePath, Pattern<TYPE>().apply(block) as Evaluable<Any>, false))
        return
    }


    inline fun <reified TYPE: Any> pathOfType(crossinline path: TARGET.() -> Any?, block: Pattern<TYPE>.() -> Unit)
    {
        val safePath = wrapAnySafe<TARGET, TYPE>(path, null)
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(safePath, Pattern<TYPE>().apply(block) as Evaluable<Any>, false))
        return
    }

    inline fun <reified TYPE: Any> ifPathExists(crossinline path: TARGET.() -> TYPE?, block: Pattern<TYPE>.() -> Unit)
    {
        val safePath = wrapCastSafe(path, null)
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(safePath, Pattern<TYPE>().apply(block) as Evaluable<Any>, true))
        return
    }

    inline fun <reified TYPE: Any> ifPathExistsOfType(crossinline path: TARGET.() -> Any?, block: Pattern<TYPE>.() -> Unit)
    {
        val safePath = wrapAnyCastSafe<TARGET, TYPE>(path, null)
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(safePath, Pattern<TYPE>().apply(block) as Evaluable<Any>, true))
        return
    }

    fun check(condition: TARGET.() -> Boolean)
    {
        val evaluable = Evaluable<TARGET> { target -> wrapNullSafe(condition, false).invoke(target) }
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(Functions::identity, evaluable, false)
                as Triple<TARGET.() -> Any, Evaluable<Any>, Boolean>)
        return
    }

    fun checkIfExists(condition: TARGET.() -> Boolean)
    {
        val evaluable = Evaluable<TARGET> { target -> wrapNullSafe(condition, true).invoke(target) }
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(Functions::identity, evaluable, true)
                as Triple<TARGET.() -> Any, Evaluable<Any>, Boolean>)
        return
    }

    fun or(disjunction: Pattern<TARGET>.() -> Unit)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(Functions::identity, pattern(Reducers.OR, disjunction), false)
                as Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>
        )
        return
    }

    fun and(conjunction: Pattern<TARGET>.() -> Unit)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(Functions::identity, pattern(Reducers.AND, conjunction), false)
                as Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>
        )
        return
    }

    fun not(evaluable: Evaluable<TARGET>)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(Functions::identity, Evaluable<TARGET>{ target -> evaluable.evaluate(target).not() }, false)
                as Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>
        )
        return
    }

    // TODO: Rework method to reduce instantiation of Pattern class
    fun <TYPE: Collection<ELEMENT>, ELEMENT: Any> anyOf(path: TARGET.() -> TYPE, block: Pattern<ELEMENT>.() -> Unit)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(path, Evaluable<TYPE>{ coll ->
            if (coll.isEmpty()) return@Evaluable false
            coll.map { elem -> pattern(initializer=block).evaluate(elem) }.run(Reducers.OR)
        }, false)
                as Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>
        )
        return
    }

    // TODO: Rework method to reduce instantiation of Pattern class
    fun <TYPE: Collection<ELEMENT>, ELEMENT: Any> allOf(path: TARGET.() -> TYPE, block: Pattern<ELEMENT>.() -> Unit)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(path, Evaluable<TYPE>{ coll ->
            if (coll.isEmpty()) return@Evaluable true
            coll.map { elem -> pattern(initializer=block).evaluate(elem) }.run(Reducers.AND)
        }, false)
                as Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>
        )
        return
    }

    // TODO: Rework method to reduce instantiation of Pattern class
    fun <TYPE: Collection<ELEMENT>, ELEMENT: Any> noneOf(path: TARGET.() -> TYPE, block: Pattern<ELEMENT>.() -> Unit)
    {
        @Suppress("UNCHECKED_CAST")
        this.conditions.add(Triple(path, Evaluable<TYPE>{ coll ->
            if (coll.isEmpty()) return@Evaluable true
            coll.map { elem -> pattern(initializer=block).evaluate(elem).not() }.run(Reducers.AND)
        }, false)
                as Triple<TARGET.() -> Any?, Evaluable<Any>, Boolean>
        )
        return
    }

    override fun evaluate(target: TARGET): Boolean
    {
        if (this.conditions.size == 0) return true
        return this.conditions.map {
            val scopedTarget = it.first(target) ?: return it.third
            it.second.evaluate(scopedTarget)
        }.run(reductionFunction)
    }

}

fun <TARGET: Any> pattern(
    reductionFunction: List<Boolean>.() -> Boolean = Reducers.AND,
    initializer: Pattern<TARGET>.() -> Unit
): Pattern<TARGET> = Pattern<TARGET>(reductionFunction).apply(initializer)

// TODO: Check whether or not there are other solutions for null safety
inline fun <TARGET, TYPE> wrapNullSafe(crossinline f: (TARGET) -> TYPE, default: TYPE): (TARGET) -> TYPE =
    { target -> try { f(target) } catch (exc: NullPointerException) { default } }

inline fun <TYPE1, reified TYPE2> wrapCastSafe(crossinline f: (TYPE1) -> TYPE2?, default: TYPE2?): (TYPE1) -> TYPE2? =
    { target -> try { f(target) } catch (exc: ClassCastException) { default } }

inline fun <TYPE1, reified TYPE2> wrapAnyCastSafe(crossinline f: (TYPE1) -> Any?, default: TYPE2?): (TYPE1) -> TYPE2? =
    { target -> try { f(target) as TYPE2? } catch (exc: ClassCastException) { default } }

inline fun <TARGET, reified TYPE> wrapSafe(crossinline f: (TARGET) -> TYPE?, default: TYPE?): (TARGET) -> TYPE? =
    { target -> try { f(target) as TYPE } catch (exc: NullPointerException) { default } catch (exc: ClassCastException) { default } }

inline fun <TARGET, reified TYPE> wrapAnySafe(crossinline f: (TARGET) -> Any?, default: TYPE?): (TARGET) -> TYPE? =
    { target -> try { f(target) as TYPE } catch (exc: NullPointerException) { default } catch (exc: ClassCastException) { default } }

@Suppress("UNCHECKED_CAST")
inline fun <reified TYPE1, reified TYPE2> b(crossinline f: (TYPE1) -> Any?, default: TYPE2?): (TYPE1) -> TYPE2? =
    { target -> try { f(target) as TYPE2 } catch (exc: ClassCastException) { default } }
