package org.medic.cxx

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.Include
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ICriterion
import ca.uhn.fhir.rest.gclient.IParam
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import kotlinx.coroutines.*
import org.hl7.fhir.r4.model.*
import kotlin.io.path.Path
import org.medic.cxx.evaluation.pattern
import org.medic.cxx.util.csv.listByLine
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.Executors
import kotlin.reflect.typeOf


private val threadPoolSize = Runtime.getRuntime().availableProcessors()
private val threadPool = Executors.newFixedThreadPool(threadPoolSize)
private val dispatcher = threadPool.asCoroutineDispatcher()
private val scope = CoroutineScope(dispatcher)

private val fhirCtx = FhirContext.forR4()

@OptIn(ExperimentalStdlibApi::class)
suspend fun main(args: Array<String>)
{

    val parser = ArgParser("argParser")
    val input by parser.option(ArgType.String, "input", "i", description = "Input file").required()
    val output by parser.option(ArgType.String, "output", "o", description = "Output file").required()
    val serverUrl by parser.option(ArgType.String, "server-url", "s", description = "FHIR server URL").required()
    parser.parse(args)

    val queryResultFilePath = Path(input)
    val fhirClient = fhirCtx.newRestfulGenericClient(serverUrl)
    val currentDate = Date()
    val idGenerator = Random()

    // Build Consent evaluation pattern
    val consentPattern = pattern<Consent> {
        // Active consent resource
        check { status == Consent.ConsentState.ACTIVE }
        path({ provision }) {
            path({ period }) {
                check { start < currentDate }
                checkIfExists { end > currentDate }
            }
            anyOf({ purpose }) {
                check { system == "https://fhir.centraxx.de/system/consent/type" }
                check { code == "2IC" }
                check { display == "2. IC Biomaterial ICB-L" }
            }
        }
    }

    // Build specimen evaluation pattern
    val specimenPattern = pattern<Specimen> {
        // Exclude aliquot groups as they do not represent real physical samples
        noneOf({ extension }) {
            check { url == "https://fhir.centraxx.de/extension/sampleCategory" }
            path({ value as Coding? }) {
                check { system == "https://fhir.centraxx.de/system/sampleCategory" }
                check { code == "ALIQUOTGROUP" }
                check { display == "Aliquotgruppe" }
            }
        }
        // Container shall not be empty
        anyOf({ container }) {
            path({ specimenQuantity }) {
                check { value > BigDecimal.ZERO }
            }
        }
    }

    // streamByLine(queryResultFilePath).map { it.joinToString(", ") }.forEach{ println(it) }
    coroutineScope {
        listByLine(queryResultFilePath).map { cells ->
            async {
                val processingId = idGenerator.nextLong().toHexString()

                // Retrieve Specimen resource, associated Patient, and Consent resources associated with Patient
                // resource
                println("[$processingId] Retrieving FHIR data for OID ${cells[0]}")
                val filters = listOf(Specimen.RES_ID.exactly().code(cells[0]))
                val includes = listOf(Specimen.INCLUDE_PATIENT)
                val revIncludes = listOf(Consent.INCLUDE_PATIENT.asRecursive())
                val specimenAndPatientBundle: Bundle = requestSpecimen(fhirClient, filters, includes, revIncludes)

                // Retrieve Specimen and Consent resource from Bundle resource
                println("[$processingId] Retrieved FHIR data from server. Start processing")
                val mappedBundle = specimenAndPatientBundle.entry.groupBy { entry -> entry.resource.resourceType }
                val specimen = mappedBundle[ResourceType.Specimen]?.get(0)?.resource as Specimen?
                if (specimen == null) {
                    println("[$processingId] WARNING: No Specimen resource in Bundle. Skipping")
                }
                else {
                    // If Specimen resource is present, continue processing
                    val consents = mappedBundle[ResourceType.Consent]?.map { entry -> entry.resource as Consent }
                    if (consents != null &&
                        consents.map { c -> consentPattern.evaluate(c) }.reduce(Boolean::or) &&
                        specimenPattern.evaluate(specimen))
                    {
                        // TODO: Further processing
                        println("[$processingId] Resources matched pattern. Exporting")
                    }
                    else {
                        println("[$processingId] Resources did not match patterns. Discarding")
                    }
                }
            }
        }.awaitAll()
    }

}

fun requestSpecimen(
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