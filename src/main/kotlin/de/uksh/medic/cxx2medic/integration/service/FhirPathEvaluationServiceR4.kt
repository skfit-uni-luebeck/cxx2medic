package de.uksh.medic.cxx2medic.integration.service

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.fhirpath.FhirPathExecutionException
import de.uksh.medic.cxx2medic.exception.UnsupportedValueException
import de.uksh.medic.cxx2medic.fhir.query.FhirQuery
import de.uksh.medic.cxx2medic.util.evaluateToBoolean
import de.uksh.medic.cxx2medic.util.getResourceTypeR4
import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.fhirpath.FHIRPathEngine
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.DateType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.text.SimpleDateFormat

@Service
class FhirPathEvaluationServiceR4(
    @Autowired fhirQuery: FhirQuery
) {
    private val fhirContext: FhirContext = FhirContext.forR4Cached()
    private val engine: FHIRPathEngine = FHIRPathEngine(HapiWorkerContext(fhirContext, fhirContext.validationSupport))
        .apply { this.isAllowPolymorphicNames = true }
    val query: FhirQuery = fhirQuery.insertConstants()

    fun evaluate(resources: List<Base>): Boolean
    {
        val typeMap = resources.associateBy { it.fhirType() }
        if (typeMap.size < resources.size)
            throw IllegalArgumentException("Provided FHIR resources have to have a unique FHIR resource type")
        val evaluatedVariables = evaluateVariables(query, typeMap)
        val actualQuery = query.insertEvaluatedVariables(evaluatedVariables)
        return evaluate(actualQuery.criteria, typeMap)
    }

    private fun evaluate(clause: FhirQuery.AndClause, map: Map<String, Base>): Boolean =
        clause.expressions.all { evaluate(it, map) } && clause.orClauses.all { evaluate(it, map) }

    private fun evaluate(clause: FhirQuery.OrClause, map: Map<String, Base>): Boolean =
        clause.expressions.run { isEmpty() || any { evaluate(it, map) } }
                || clause.andClauses.run { isEmpty() || any { evaluate(it, map) } }

    private fun evaluate(expression: String, map: Map<String, Base>): Boolean
    {
        try {
            val resourceType = getResourceTypeR4(expression).toCode()
            val resource = map[resourceType] ?:
                throw IllegalArgumentException("Missing resource of type '${resourceType}'")
            try { return engine.evaluateToBoolean(resource, expression) }
            catch (exc: Exception) {
                throw FhirPathExecutionException("Failed to evaluate FHIRPath expression against resource " +
                        "[type=${resource.fhirType()}, id=${resource.idBase}]", exc)
            }
        }
        catch (exc: Exception) {
            throw Exception("Could not evaluate expression '${expression}'", exc)
        }
    }

    private fun evaluateVariables(query: FhirQuery, map: Map<String, Base>): Map<String, String>
    {
        return query.variables.mapValues { e ->
            val resourceType = getResourceTypeR4(e.value).toCode()
            val results = kotlin.runCatching { engine.evaluate(map[resourceType]!!, e.value) }.getOrElse { exc ->
                throw FHIRException("Failed to resolve variable", exc)
            }
            if (results.isNotEmpty()) results[0]
            else throw NoSuchElementException("Cannot resolve variable ${e.key}. " +
                    "No such elements in resource [expr=${e.value}]")
        }.mapValues { e ->
            when (val value = e.value) {
                is DateType -> fhirPathDateFormatter.format(value.value)
                else -> throw UnsupportedValueException("Type ${value.fhirType()} is currently not supported")
            }
        }
    }

    companion object
    {
        private val fhirPathDateFormatter = SimpleDateFormat("YYYY-MM-dd")
    }
}