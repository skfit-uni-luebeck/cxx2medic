package org.medic.cxx

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.Include
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ICriterion
import ca.uhn.fhir.rest.gclient.IParam
import ca.uhn.fhir.rest.gclient.IQuery
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.buffer
import org.hl7.fhir.r4.model.Specimen
import kotlin.io.path.Path
import org.medic.cxx.util.csv.iterateByLine
import org.medic.cxx.util.csv.streamByLine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.forEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.stream.consumeAsFlow
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Consent
import org.medic.cxx.evaluation.EvaluationPattern
import org.medic.cxx.util.csv.listByLine
import java.util.concurrent.Executors
import kotlin.streams.toList

private val threadPoolSize = Runtime.getRuntime().availableProcessors()
private val threadPool = Executors.newFixedThreadPool(threadPoolSize)
private val dispatcher = threadPool.asCoroutineDispatcher()
private val scope = CoroutineScope(dispatcher)

private val fhirCtx = FhirContext.forR4()

suspend fun main(args: Array<String>)
{

    val parser = ArgParser("argParser")
    val input by parser.option(ArgType.String, "input", "i", description = "Input file").required()
    val output by parser.option(ArgType.String, "output", "o", description = "Output file").required()
    val serverUrl by parser.option(ArgType.String, "server-url", "s", description = "FHIR server URL").required()
    parser.parse(args)

    val queryResultFilePath = Path(input)
    val fhirClient = fhirCtx.newRestfulGenericClient(serverUrl)

    // Build Consent evaluation pattern
    val consentPattern = EvaluationPattern<Consent>()
        .addCriterion({ v -> this.status == v }, Consent.ConsentState.ACTIVE)
        .addCriterion({ _ -> this.provision != null }, Unit)

    // streamByLine(queryResultFilePath).map { it.joinToString(", ") }.forEach{ println(it) }
    coroutineScope {
        listByLine(queryResultFilePath).map { cells ->
            async {
                // Retrieve Specimen resource, associated Patient, and Consent resources associated with Patient
                // resource
                val filters = listOf(Specimen.RES_ID.exactly().code(cells[0]))
                val includes = listOf(Specimen.INCLUDE_PATIENT)
                val revIncludes = listOf(Consent.INCLUDE_PATIENT.asRecursive())
                val specimenAndPatientBundle: Bundle = requestSpecimen(fhirClient, filters, includes, revIncludes)

                //
            }
        }.awaitAll()
    }

}

suspend fun requestSpecimen(
    client: IGenericClient,
    filters: List<ICriterion<out IParam>>?,
    includes: List<Include>?,
    revIncludes: List<Include>?
): Bundle
{
    val query = client.search<Bundle>()
        .forResource(Specimen::class.java)
        .include(Specimen.INCLUDE_SUBJECT)
    filters?.forEach { filter -> query.where(filter)}
    includes?.forEach { include -> query.include(include)}
    revIncludes?.forEach {revInclude -> query.revInclude(revInclude)}
    return query.execute()
}

// TODO: Try a generic approach
fun confirmConsent(consent: Consent): Boolean
{
    return with(consent) {
        if (status != Consent.ConsentState.ACTIVE) return false

        val test: Consent.() -> Boolean = { status != null }

        with(provision) {

        }

        return true
    }
}