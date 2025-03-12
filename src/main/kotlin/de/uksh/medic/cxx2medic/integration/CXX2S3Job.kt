package de.uksh.medic.cxx2medic.integration

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ca.uhn.fhir.context.FhirContext
import de.uksh.medic.cxx2medic.config.CentraXXSettings
import de.uksh.medic.cxx2medic.exception.UnknownChangeTypeException
import de.uksh.medic.cxx2medic.fhir.query.FhirQuery
import de.uksh.medic.cxx2medic.integration.aggregator.strategy.SequenceAwareMessageCountReleaseStrategy
import de.uksh.medic.cxx2medic.integration.handler.S3StorageWriterHandler
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.CentraXXFhirService
import de.uksh.medic.cxx2medic.integration.service.FhirPathEvaluationServiceR4
import de.uksh.medic.cxx2medic.integration.service.S3StorageService
import de.uksh.medic.cxx2medic.util.Identifiers
import de.uksh.medic.cxx2medic.util.dataIsAbsentBecause
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.Bundle.HTTPVerb
import org.hl7.fhir.r4.model.Enumerations.DataAbsentReason
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.IntegrationMessageHeaderAccessor
import org.springframework.integration.aggregator.HeaderAttributeCorrelationStrategy
import org.springframework.integration.channel.PublishSubscribeChannel
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.core.MessageSource
import org.springframework.integration.dsl.integrationFlow
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.support.CronTrigger
import java.math.BigDecimal
import java.time.Instant
import java.util.*

private val logger: Logger = LogManager.getLogger(CXX2S3Job::class.java)

@Configuration
@EnableIntegration
class CXX2S3Job(
    @Autowired private val s3Service: S3StorageService,
    @Autowired cxxSettings: CentraXXSettings,
    @Autowired fhirQuery: FhirQuery
)
{
    init
    {
        when (val expr = cxxSettings.patientReferenceIdentifier) {
            null -> logger.warn("No config entry 'cxx.patientReferenceIdentifier' is present in application settings " +
                    "file. It is used to extract an identifier from the associated Patient instance to use as a " +
                    "subject reference in the Specimen instance. If it is not provided no such reference will be " +
                    "present. Ignore this message only if you are able to link the data in some other way")
            else -> logger.info("Patient reference will be generated using identifier locatable via FHIRPath " +
                    "expression '$expr'")
        }

        if ("Consent" !in fhirQuery.getInvolvedFhirTypes()) {
            logger.warn("Since no criterion targets the FHIR Consent resource type its presence will not be a " +
                    "requirement for the export of a specimen")
        }
    }

    @Bean
    fun readCentraxxDatabase(
        @Autowired @Qualifier("cxx:msg-source") source: MessageSource<List<Map<String, String?>>>,
        @Autowired @Qualifier("global:trigger") trigger: CronTrigger,
        @Autowired  @Qualifier("global:trigger-ctx") triggerContext: UpToDateTriggerContext
    ) = integrationFlow(source, { poller { it.trigger(trigger) } }) {
        enrichHeaders {
            header("runTimestamp", triggerContext.currentExecution())
            header("runId", UUID.nameUUIDFromBytes(triggerContext.currentExecution().toString().encodeToByteArray()))
            //header("consentPattern", consentPattern(triggerContext.currentExecution()))
        }
        split<List<Map<String, String?>>> { it }
        enrichHeaders {
            headerExpression("specimenId", "payload['specimen_id']")
            headerExpression("patientId", "payload['patient_id']")
            headerExpression("consentId", "payload['consent_id']")
        }
        route<Map<String, String?>> { row ->
            val specimenId = row["specimen_id"]
            val patientId = row["patient_id"]
            val consentId = row["consent_id"]
            if (specimenId == null) {
                logger.warn("Missing specimen ID [patientId=${patientId}, consentId=${consentId}] => Discarding")
                "nullChannel"
            } else if (patientId == null) {
                logger.info("Missing patient ID [specimenId=${specimenId}] => Deleting")
                "mark-for-deletion"
            } else if (consentId == null) {
                logger.debug("Missing consent ID [specimenId=${specimenId}]")
                "add-headers"
            } else "add-headers"
        }
        //channel("add-headers")
    }

    @Bean
    fun addHeaders() = integrationFlow("add-headers") {
        transform<Message<Map<String, String?>>> { msg: Message<Map<String, String?>> ->
            val m = msg.payload
            val specimenId = m["specimen_id"]!!
            val patientId = m["patient_id"]!!
            val consentId = m["consent_id"]
            MessageBuilder.withPayload(mapOf(
                "Specimen" to specimenId,
                "Patient" to patientId,
                "Consent" to consentId
            )).copyHeaders(msg.headers)
                .setHeader("request", when (val changeType = m["change_kind"]!!) {
                    "I" -> HTTPVerb.POST    // create
                    "U" -> HTTPVerb.PUT     // update
                    "D" -> HTTPVerb.DELETE  // delete
                    else -> throw UnknownChangeTypeException(changeType)
                })
                .build()
        }
        channel("cxx-db-data")
    }

    @Bean
    fun routeByChangeKind() = integrationFlow("cxx-db-data") {
        route<Message<Map<String, String?>>> { m ->
            when (val verb = m.headers["request"] as HTTPVerb) {
                HTTPVerb.POST, HTTPVerb.PUT -> "cxx-db-data-query-facade"
                HTTPVerb.DELETE -> "cxx-db-data-ignore-content"
                else -> {
                    logger.warn("Unsupported request method (change kind) $verb => Discarding")
                    "nullChannel"
                }
            }
        }
    }

    @Bean
    fun readCentraxxFhirFacade(
        @Autowired fhirService: CentraXXFhirService
    ) = integrationFlow("cxx-db-data-query-facade") {
        transform<Message<Map<String, String?>>> { msg: Message<Map<String, String?>> ->
            msg.payload.mapValues {
                if (it.value != null) fhirService.read(it.value!!, it.key).onNone {
                    logger.debug(
                        "Could not find {} instance with ID '{}' [specimenId={}]",
                        it.key, it.value, msg.headers["specimenId"]
                    )
                }
                else {
                    logger.debug(
                        "No ID to retrieve instance of type {} with [specimenId={}]",
                        it.key, msg.headers["specimenId"]
                    )
                    None
                }
            }
            //val m = msg.payload
            //val specimen = fhirService.readSpecimen(m.first)
            //val patient = fhirService.readPatient(m.second)
            //val consent = fhirService.readConsent(m.third)
            //Triple(specimen, consent, patient)
        }
        channel("cxx-fhir-data")
    }

    @Bean
    fun routeBasedOnCriteria(
        @Autowired evaluationService: FhirPathEvaluationServiceR4
    ) = integrationFlow("cxx-fhir-data") {
        route<Message<Map<String, Option<IBaseResource>>>> { m ->
            val keepChannel = "filtered-fhir-data"
            val deleteChannel = "mark-for-deletion"
            val involvedFhirTypes = evaluationService.query.getInvolvedFhirTypes()
            val channel = kotlin.runCatching {
                val list = m.payload.entries.filter {
                    when (it.value) {
                        is None -> {
                            if (it.key in involvedFhirTypes) {
                                logger.warn("Missing ${it.key} resource required for evaluation => Deleting")
                                return@runCatching deleteChannel
                            }
                            false
                        }
                        is Some -> true
                    }
                }.map { it.value.getOrNull()!! as Base }
                return@runCatching if (evaluationService.evaluate(list)) keepChannel
                else deleteChannel
            }.getOrElse { exc ->
                when (exc) {
                    is NoSuchElementException -> logger.warn("${exc.message} => Deleting")
                    else -> logger.warn("Failed to evaluate criteria [id=${m.headers["specimenId"]!!}] " +
                            "=> Deleting", exc)
                }
                deleteChannel
            }
            logger.info("Evaluated Specimen [id=${m.headers["specimenId"]!!}] => "  +
                    if (channel == keepChannel) "Keeping" else "Deleting")
            channel
        }
    }

    @Bean
    fun enrichSpecimen(
        @Autowired cxxSettings: CentraXXSettings,
        @Autowired evalService: FhirPathEvaluationServiceR4
    ) = integrationFlow("filtered-fhir-data") {
        transform<Message<Map<String, Option<IBaseResource>>>> { msg ->
            // Due to the previous step the values cannot be null or None so they can be unpacked safely
            val m = msg.payload
            val specimen = m["Specimen"]!!.getOrNull()!! as Specimen
            @Suppress("UNCHECKED_CAST")
            val oConsent = m["Consent"]!! as Option<Consent>
            val patient = m["Patient"]!!.getOrNull()!! as Patient
            // FIXME: Add proper request type adjustment based on current request type similar to criteria definition
            //        and evaluation
            val requestType = msg.headers["request"] as HTTPVerb

            logger.info("Processing Specimen resource [id=${specimen.idPart}, requestType=$requestType]")

            if (cxxSettings.patientReferenceIdentifier != null) {
                evalService.retrieve<Identifier>(patient, cxxSettings.patientReferenceIdentifier).fold(
                    { ids -> when (ids.size) {
                        0 -> {
                            logger.warn("No suitable patient identifier could be found [patientId=${patient.idPart}]. " +
                                    "No reference will be present")
                            specimen.setSubject(
                                Reference().apply { type = "Patient" } dataIsAbsentBecause DataAbsentReason.NOTAPPLICABLE
                            )
                        }
                        else -> {
                            if (ids.size > 1) logger.warn("More than one patient identifier matches " +
                                    "[patientId=${patient.idPart}]. Using first match")
                            specimen.setSubject(Reference().apply {
                                identifier = ids[0]
                                type = "Patient"
                            })
                        }
                    } },
                    { exc ->
                        logger.warn("Failed to retrieve patient identifier [patientId=${patient.idPart}]", exc)
                        specimen.setSubject(
                            Reference().apply { type = "Patient" } dataIsAbsentBecause DataAbsentReason.ERROR
                        )
                    }
                )
            }

            specimen.extension.add(Extension().apply {
                url = "https://medic.uksh.de/fhir/StructureDefinition/ext-specimen-consent-identifier"
                setValue(Identifier().apply {
                    system = Identifiers.BIOBANK_CENTRAXX_CONSENT
                    when (oConsent) {
                        is None -> addExtension().apply {
                                url = Identifiers.DATA_ABSENT_REASON
                                setValue(CodeType("unknown"))
                            }
                        is Some -> value = oConsent.getOrNull()!!.idPart
                    }
                })
            })
            specimen.extension.add(Extension().apply {
                url = "https://www.medizininformatik-initiative.de/fhir/ext/modul-biobank/StructureDefinition/VerwaltendeOrganisation"
                setValue(cxxSettings.managingOrg)
            })

            val entry = BundleEntryComponent().apply {
                fullUrl = "${Identifiers.BIOBANK_CENTRAXX_SPECIMEN_OID}/${specimen.idPart}"
                resource = specimen
                request.method = msg.headers["request"] as HTTPVerb
                request.url = when (request.method) {
                    HTTPVerb.PUT -> "{protocol}://{openehr_base_url}/rest/v1/ehr/{ehr_id}/composition/{uid_based_id}"
                    else -> "{protocol}://{openehr_base_url}/rest/v1/ehr/{ehr_id}/composition"
                }
            }

            MessageBuilder.withPayload(entry)
                .copyHeaders(msg.headers)
                .setHeader("request", requestType).build()
        }
        channel("specimen-fhir-data")
    }

    @Bean
    fun markForDeletion() = integrationFlow("mark-for-deletion") {
        enrichHeaders {
            header("request", HTTPVerb.DELETE, true)
        }
        channel("cxx-db-data-ignore-content")
    }

    @Bean
    fun processChangesWithoutContent() = integrationFlow("cxx-db-data-ignore-content") {
        transform<Message<*>> { msg ->
            val specimenId = msg.headers["specimenId"]!!
            val requestType = msg.headers["request"]!! as HTTPVerb
            logger.info("Processing specimen entry [id=$specimenId, requestType=$requestType]")
            BundleEntryComponent().apply {
                fullUrl = "${Identifiers.BIOBANK_CENTRAXX_SPECIMEN_OID}/${specimenId}"
                request.method = requestType
                request.url =
                    "{protocol}://{openehr_base_url}/rest/openehr/v1/ehr/{ehr_id}/composition/{preceding_version_uid}"
            }
        }
        channel("specimen-fhir-data")
    }

    @Bean
    fun aggregateSpecimenToBundles(
        @Autowired bundleSizeLimit: Int
    ) = integrationFlow("specimen-fhir-data") {
        //resequence {
        //    correlationStrategy(HeaderAttributeCorrelationStrategy(IntegrationMessageHeaderAccessor.CORRELATION_ID))
        //}
        aggregate {
            expireGroupsUponCompletion(true)
            releaseStrategy(SequenceAwareMessageCountReleaseStrategy(bundleSizeLimit))
            correlationStrategy(HeaderAttributeCorrelationStrategy(IntegrationMessageHeaderAccessor.CORRELATION_ID))
            groupTimeout(10000)
            sendPartialResultOnExpiry(true)
        }
        transform<List<BundleEntryComponent>> { list ->
            Bundle().apply {
                id = UUID.randomUUID().toString()
                type = Bundle.BundleType.BATCH
                timestamp = Date.from(Instant.now())
                total = list.size
                entry.addAll(list)
            }.also { logger.info("Created bundle [id=${it.idPart}, size=${it.total}]") }
        }
        enrichHeaders {
            headerExpression(S3StorageWriterHandler.OBJECT_NAME_HEADER, "payload.id + \".json\"")
        }
        channel("specimen-bundle-data")
    }

    @Bean
    fun encodeAndStore(
        @Autowired fhirContext: FhirContext,
        @Autowired bucketName: String,
    ) = integrationFlow("specimen-bundle-data") {
        transform<Bundle> {
            val content = fhirContext.newJsonParser().apply { setPrettyPrint(false) }.encodeResourceToString(it)
            logger.info("Parsed bundle [id=${it.idPart}, contentLength=${content.encodeToByteArray().size}]")
            content
        }
        enrichHeaders {
            header(S3StorageWriterHandler.BUCKET_NAME_HEADER, bucketName)
            header(MessageHeaders.CONTENT_TYPE, "application/fhir+json")
        }
        channel(PublishSubscribeChannel().apply {
            beanName = "specimen-bundle-raw"
            subscribe(S3StorageWriterHandler(s3Service))
        })
    }
}