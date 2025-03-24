package de.uksh.medic.cxx2medic.integration.service

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.FhirPathExecutionException
import de.uksh.medic.cxx2medic.exception.UnsupportedValueException
import de.uksh.medic.cxx2medic.fhir.query.FhirQuery
import de.uksh.medic.cxx2medic.util.evaluateToBoolean
import de.uksh.medic.cxx2medic.util.getResourceTypeR4
import org.apache.logging.log4j.LogManager
import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.fhirpath.FHIRPathEngine
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.DateType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.text.SimpleDateFormat
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.log

@Service
class FhirPathEvaluationServiceR4(
    @Autowired fhirQuery: FhirQuery
) {
    private val fhirContext: FhirContext = FhirContext.forR4Cached()
    private val engine: FHIRPathEngine = FHIRPathEngine(HapiWorkerContext(fhirContext, fhirContext.validationSupport))
        .apply { this.isAllowPolymorphicNames = true }
    val query: FhirQuery = fhirQuery.insertConstants()

    fun evaluate(coll: Bundle): Boolean
    {
        // Partition variable by whether they could be evaluated successfully
        val (ss, ns) = evaluateVariables(query, coll).entries.partition { it.value.isSome() }
        val evaluatedVariables = ss.associate { it.key to it.value.getOrNull()!! }
        // Prepare actual query to execute
        val actualQuery = if (ns.isNotEmpty()) {
            logger.warn("Variables [${ns.joinToString { "'${it.key}'" }}] could not be evaluated and dependent " +
                    "expressions will thus automatically evaluate to false")
            val cs = query.criteria.replace({ expr -> ns.any { expr.contains(it.key) } }, "false")
            FhirQuery(
                query.description, query.constants, query.variables.minus(ns.map { it.key }.toSet()),
                cs.insertPlaceholders(evaluatedVariables)
            )
        } else query.insertEvaluatedVariables(evaluatedVariables)
        return evaluate(actualQuery.criteria, coll)
    }

    fun <T> retrieve(resource: Base, expr: String): Result<List<T>> =
        kotlin.runCatching { engine.evaluate(resource, expr) }.map { it as List<T> }

    private fun evaluate(clause: FhirQuery.AndClause, coll: Bundle): Boolean =
        clause.expressions.all { evaluate(it, coll) } && clause.orClauses.all { evaluate(it, coll) }

    private fun evaluate(clause: FhirQuery.OrClause, coll: Bundle): Boolean =
        clause.expressions.any { evaluate(it, coll) } || clause.andClauses.any { evaluate(it, coll) } || clause.isEmpty()

    private fun evaluate(expression: String, coll: Bundle): Boolean
    {
        try {
            // Only expand expression when necessary
            val expandedExpr = when(expression) {
                ALWAYS_TRUE_EXPR -> expression
                ALWAYS_FALSE_EXPR -> expression
                else -> expandToBundleExpression(expression)
            }
            try { return engine.evaluateToBoolean(coll, expandedExpr) }
            catch (exc: Exception) {
                throw FhirPathExecutionException("Failed to evaluate FHIRPath expression against Bundle resource " +
                        "[expr=$expandedExpr]", exc)
            }
        }
        catch (exc: Exception) {
            throw Exception("Could not evaluate expression '${expression}'", exc)
        }
    }

    private fun evaluateVariables(query: FhirQuery, coll: Bundle): Map<String, Option<String>>
    {
        return query.variables.mapValues { e ->
            val expandedExpr = expandToBundleExpression(e.value)
            val results = kotlin.runCatching { engine.evaluate(coll, expandedExpr) }.getOrElse { exc ->
                throw FHIRException("Failed to resolve variable '${e.key}'", exc)
            }
            when (results.size) {
                0 -> {
                    logger.debug("Cannot resolve variable ${e.key}. No such elements in resource or no such resource " +
                            "exists [expr=${expandedExpr}]")
                    None
                }
                1 -> Some(results[0])
                else -> {
                    logger.debug("More than one result was found for variable '${e.key}'. Returning first")
                    Some(results[0])
                }
            }
        }.mapValues { e ->
            e.value.map { when (it) {
                is DateType -> fhirPathDateFormatter.format(it.value)
                else -> it.primitiveValue()
            } }
        }
    }

    companion object
    {
        private val fhirPathDateFormatter = SimpleDateFormat("YYYY-MM-dd")
        private val dotRegex: Regex = Regex("\\.")
        private val logger = LogManager.getLogger(FhirPathEvaluationServiceR4::class.java)

        const val ALWAYS_FALSE_EXPR = "false"
        const val ALWAYS_TRUE_EXPR = "true"

        private fun expandToBundleExpression(expr: String) =
            "Bundle.entry.resource.where(\$this is ${getResourceTypeR4(expr).toCode()}).${expr.split(dotRegex, 2)[1]}"
    }
}