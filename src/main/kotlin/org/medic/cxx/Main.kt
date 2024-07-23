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
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import kotlin.io.path.Path
import org.medic.cxx.evaluation.pattern
import org.medic.cxx.util.csv.listByLine
import org.medic.cxx.util.http.POST
import java.math.BigDecimal
import java.util.*


private val fhirCtx = FhirContext.forR4()
private val jsonParser = fhirCtx.newJsonParser()

@OptIn(ExperimentalStdlibApi::class)
suspend fun main(args: Array<String>)
{

    val parser = ArgParser("argParser")
    val inputFile by parser.option(ArgType.String, "input-file", "i", description = "Input file").required()
    val targetUrl by parser.option(ArgType.String, "target-url", "t", description = "Target URL").required()
    val serverUrl by parser.option(ArgType.String, "server-url", "s", description = "FHIR server URL").required()
    parser.parse(args)

    val queryResultFilePath = Path(inputFile)
    val fhirClient = fhirCtx.newRestfulGenericClient(serverUrl)
    val currentDate = Date()
    val idGenerator = Random()

    // NOTE: The most "FHIR" way would be using StructureDefinition resources with encoded constraints (probably)

    // Build Consent evaluation pattern
    val consentPattern = pattern<Consent> {
        // Active consent resource
        check { status == Consent.ConsentState.ACTIVE }
        path({ provision }) {
            // Provision validity has to include current point in time
            path({ period }) {
                check { start < currentDate }
                checkIfExists { end > currentDate }
            }
            // Purpose reflecting consent type required for export
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
            pathOfType<Coding>({ value }) {
                check { system == "https://fhir.centraxx.de/system/sampleCategory" }
                check { code == "ALIQUOTGROUP" }
                check { display == "Aliquotgruppe" }
            }
        }
    }

    val identifierPattern = pattern<Identifier> {
        anyOf({ type.coding }) {
            check { system == "https://fhir.centraxx.de/system/idContainerType" }
            check { code == "KIS-ID" }
            check { display == "ORBIS-ID" }
        }
    }

    // streamByLine(queryResultFilePath).map { it.joinToString(", ") }.forEach{ println(it) }
    coroutineScope {

        val patientMap = mutableMapOf<String, Patient>()
        val consentMap = mutableMapOf<String, Consent>()

        listByLine(queryResultFilePath).map { cells ->
            async {
                val processingId = idGenerator.nextLong().toHexString()
                try {
                    val specimenId = cells[0]
                    val patientId = cells[1]
                    val consentId = cells[2]

                    // Retrieve Specimen resource, associated Patient, and Consent resources associated with Patient
                    // resource
                    println("[$processingId] Retrieving FHIR data for specimen OID $specimenId")

                    // TODO: If same patient or consent is shared by multiple Specimen instances initially requests for
                    //       the same resource might be done anyway. How to avoid this?
                    val specimen = fhirClient.read<Specimen>(specimenId)
                    if (patientId !in patientMap) patientMap[patientId] =
                        fhirClient.read<Patient>(patientId)?: throw Exception("Could not find patient with ID '$patientId'")
                    val patient = patientMap[patientId]?: throw Exception("No patient with ID '$patientId'")
                    if (consentId !in consentMap) consentMap[consentId] =
                        fhirClient.read<Consent>(consentId)?: throw Exception("Could not find patient with ID '$consentId'")
                    val consent = consentMap[consentId]?: throw Exception("No patient with ID '$consentId'")

                    //val filters = listOf(Specimen.RES_ID.exactly().code(cells[0]))
                    //val includes = listOf(Specimen.INCLUDE_PATIENT)
                    //val revIncludes = listOf(Consent.INCLUDE_PATIENT.asRecursive())
                    //val specimenAndPatientBundle: Bundle = requestSpecimen(fhirClient, filters, includes, revIncludes)

                    // Retrieve Specimen and Consent resource from Bundle resource
                    println("[$processingId] Retrieved FHIR data from server. Start processing")
                    if (specimen == null) {
                        println("[$processingId] WARNING: No Specimen resource in Bundle. Skipping")
                    }
                    else {
                        // If Specimen resource is present, continue processing
                        if (consentPattern.evaluate(consent) && specimenPattern.evaluate(specimen)) {
                            println("[$processingId] Resources matched pattern. Exporting")

                            specimen.subject.apply {
                                type = "Patient"
                                identifier = patient.identifier.filter { identifierPattern.evaluate(it) }[0]
                            }

                            specimen.extension.add(Extension().apply {
                                url = "https://fhir.medicsh.de/StructureDefinition/ext-specimen-consent-identifier"
                                setValue(Identifier().apply {
                                    type.addCoding().apply {
                                        system = "https://fhir.centraxx.de/system/idContainerType"
                                        code = "KIS-ID"
                                        display = "ORBIS-ID"
                                    }
                                    value = consentId
                                })
                            })

                            val encodedSpecimen = jsonParser.encodeToString(specimen)
                            val response = POST(targetUrl, encodedSpecimen)

                            println("[$processingId] Received response with status code ${response.statusCode()}\n${response.body()}")
                        }
                        else {
                            println("[$processingId] Resources did not match patterns. Discarding")
                        }
                    }
                }
                catch (exc: Exception) {
                    println("[$processingId] ERROR: Processing failed: ${exc.message}. Continuing\n${exc.stackTraceToString()}")
                }
            }
        }.awaitAll()
    }

}

inline fun <reified TYPE: IBaseResource> IGenericClient.read(id: String) =
    this.read().resource(TYPE::class.java).withId(id).execute()

/*
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
 */