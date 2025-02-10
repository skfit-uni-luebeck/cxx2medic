package de.uksh.medic.cxx2medic.fhir.query

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ArrayNode
import de.uksh.medic.cxx2medic.util.replaceAll
import de.uksh.medic.cxx2medic.util.getResourceTypeR4

data class FhirQuery(
    val description: String?,
    val constants: Map<String, String>,
    val variables: Map<String, String>,
    val criteria: AndClause
)
{
    init
    {
        val intersection = constants.keys intersect variables.keys
        if (intersection.isNotEmpty())
            throw IllegalArgumentException(
                "Constant and variable identifiers have to be unique [duplicates=$intersection]"
            )
    }

    fun copy(): FhirQuery = FhirQuery(
        description,
        constants.toMap(),
        variables.toMap(),
        criteria.copy()
    )

    fun insertConstants(): FhirQuery =
        FhirQuery(description, emptyMap(), variables, criteria.insertPlaceholders(constants))

    fun insertEvaluatedVariables(variables: Map<String, String>): FhirQuery =
        FhirQuery(description, constants, emptyMap(), criteria.insertPlaceholders(variables))

    fun getInvolvedFhirTypes(): Set<String> =
        variables.map { getResourceTypeR4(it.value).toCode() } union criteria.getInvolvedFhirTypes()

    @JsonDeserialize(using = AndClause.Serializer::class)
    data class AndClause(val orClauses: List<OrClause>, val expressions: List<String>)
    {
        fun copy(): AndClause = AndClause(orClauses.map { it.copy() }, expressions.toList())

        fun insertPlaceholders(constants: Map<String, String>): AndClause = AndClause(
            orClauses.map { it.insertPlaceholders(constants) },
            expressions.map { expr -> expr.replaceAll(constants.mapKeys { "\$${it.key}" }) }
        )

        fun getInvolvedFhirTypes(): Set<String> =
            expressions.map { getResourceTypeR4(it).toCode() } union
                    orClauses.map { it.getInvolvedFhirTypes() }.reduce { s1, s2 -> s1 union s2}

        class Serializer(vc: Class<*>? = null): StdDeserializer<AndClause>(vc)
        {
            override fun deserialize(parser: JsonParser, ctx: DeserializationContext?): AndClause =
                fromArrayNode(parser.codec.readTree(parser))
        }

        companion object
        {
            fun fromArrayNode(node: ArrayNode): AndClause
            {
                val clauses = node.map {
                    when {
                        it.isTextual -> it.asText()
                        it.isArray -> OrClause.fromArrayNode(it as ArrayNode)
                        else ->
                            throw JsonParseException("Unexpected node type '${it.nodeType}' for ${this::class.simpleName}")
                    }
                }.partition { it !is String } as Pair<List<OrClause>, List<String>>
                return AndClause(clauses.first, clauses.second)
            }
        }
    }

    data class OrClause(val andClauses: List<AndClause>, val expressions: List<String>)
    {
        fun copy(): OrClause = OrClause(andClauses.map { it.copy() }, expressions.toList())

        fun insertPlaceholders(constants: Map<String, String>): OrClause = OrClause(
            andClauses.map { it.insertPlaceholders(constants) },
            expressions.map { expr -> expr.replaceAll(constants.mapKeys { "\$${it.key}" }) }
        )

        fun getInvolvedFhirTypes(): Set<String> =
            expressions.map { getResourceTypeR4(it).toCode() } union
                    andClauses.map { it.getInvolvedFhirTypes() }.reduce { s1, s2 -> s1 union s2 }

        companion object
        {
            fun fromArrayNode(node: ArrayNode): OrClause
            {
                val clauses = node.map {
                    when {
                        it.isTextual -> it.asText()
                        it.isArray -> AndClause.fromArrayNode(it as ArrayNode)
                        else ->
                            throw JsonParseException("Unexpected node type '${it.nodeType}' for ${this::class.simpleName}")
                    }
                }.partition { it !is String } as Pair<List<AndClause>, List<String>>
                return OrClause(clauses.first, clauses.second)
            }
        }
    }
}