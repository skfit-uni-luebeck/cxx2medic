package org.medic.cxx2medic

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import kotlinx.coroutines.*
import org.apache.logging.log4j.LogManager
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import kotlin.io.path.Path
import org.medic.cxx2medic.evaluation.pattern
import org.medic.cxx2medic.util.csv.listByLine
import org.medic.cxx2medic.util.http.POST
import java.util.*
import kotlin.NoSuchElementException


private val fhirCtx = FhirContext.forR4()
private val jsonParser = fhirCtx.newJsonParser()

private val logger = LogManager.getLogger("CXX2NIFI")

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

    coroutineScope {

        val patientMap = Collections.synchronizedMap(mutableMapOf<String, Patient>())
        val consentMap = Collections.synchronizedMap(mutableMapOf<String, Consent>())

        listByLine(queryResultFilePath).map { cells ->
            async {
                val processingId = idGenerator.nextLong().toHexString()
                try {
                    val specimenId = cells[0]
                    val patientId = cells[1]
                    val consentId = cells[2]

                    // Retrieve Specimen resource, associated Patient, and Consent resources associated with Patient
                    // resource
                    logger.info("[$processingId] Retrieving FHIR data for specimen OID $specimenId")

                    // TODO: If same patient or consent is shared by multiple Specimen instances initially requests for
                    //       the same resource might be done anyway. How to avoid this?
                    val specimen: Specimen
                    val patient: Patient
                    val consent: Consent

                    try {
                        specimen = fhirClient.read<Specimen>(specimenId)
                        if (patientId !in patientMap) patientMap[patientId] =
                            fhirClient.read<Patient>(patientId)
                        patient = patientMap[patientId]!! // Should never be null due to previous handling
                        if (consentId !in consentMap) consentMap[consentId] =
                            fhirClient.read<Consent>(consentId)
                        consent = consentMap[consentId]!! // Should never be null due to previous handling
                    }
                    catch (exc: ResourceNotFoundException) {
                        logger.warn("[$processingId] ${exc.message}. Skipping")
                        return@async
                    }
                    catch (exc: Exception) {
                        logger.warn("[$processingId] Unexpected error occurred. Skipping:\n${exc.stackTraceToString()}")
                        return@async
                    }

                    logger.info("[$processingId] Retrieved FHIR data from server. Start processing")
                    if (consentPattern.evaluate(consent) && specimenPattern.evaluate(specimen)) {
                        logger.info("[$processingId] Resources matched patterns. Exporting")

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

                        if (response.statusCode() == 200) {
                            logger.info("[$processingId] Received response with status code ${response.statusCode()}")
                        }
                        else {
                            logger.warn("[$processingId] Unexpected response code ${response.statusCode()}. Expected 200. Continuing")
                        }

                    }
                    else {
                        logger.info("[$processingId] Resources did not match patterns. Discarding")
                    }
                }
                catch (exc: Exception) {
                    logger.warn("[$processingId] ERROR: Processing failed: ${exc.message}. Continuing\n${exc.stackTraceToString()}")
                }
            }
        }.awaitAll()
    }

}

inline fun <reified TYPE: IBaseResource> IGenericClient.read(id: String): TYPE =
    this.read().resource(TYPE::class.java).withId(id).execute()