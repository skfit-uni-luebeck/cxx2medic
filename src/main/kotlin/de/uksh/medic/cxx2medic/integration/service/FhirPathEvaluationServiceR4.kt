package de.uksh.medic.cxx2medic.integration.service

import ca.uhn.fhir.context.FhirContext
import de.uksh.medic.cxx2medic.exception.UnsupportedValueException
import de.uksh.medic.cxx2medic.fhir.query.FhirQuery
import de.uksh.medic.cxx2medic.util.evaluateToBoolean
import de.uksh.medic.cxx2medic.util.getResourceType
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
    private val query: FhirQuery = fhirQuery.insertConstants()

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
        val a = engine.evaluateToBoolean(map[getResourceType(expression)]!!, expression)
        return a
    }

    private fun evaluateVariables(query: FhirQuery, map: Map<String, Base>): Map<String, String>
    {
        return query.variables.mapValues { e ->
            val resourceType = getResourceType(e.value)
            val results = engine.evaluate(map[resourceType]!!, e.value)
            if (results.isNotEmpty()) results[0]
            else throw Exception("Cannot resolve variable ${e.key}. No such elements in resource")
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