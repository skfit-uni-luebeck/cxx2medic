package de.uksh.medic.cxx2medic.integration

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.some
import arrow.resilience.Schedule
import ca.uhn.fhir.context.FhirContext
import de.uksh.medic.cxx2medic.config.CentraXXSettings
import de.uksh.medic.cxx2medic.exception.UnknownChangeTypeException
import de.uksh.medic.cxx2medic.fhir.query.FhirQuery
import de.uksh.medic.cxx2medic.integration.aggregator.strategy.SequenceAwareMessageCountReleaseStrategy
import de.uksh.medic.cxx2medic.integration.handler.ReplayingErrorHandler
import de.uksh.medic.cxx2medic.integration.handler.S3StorageWriterHandler
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.CentraXXFhirService
import de.uksh.medic.cxx2medic.integration.service.FhirPathEvaluationServiceR4
import de.uksh.medic.cxx2medic.integration.service.S3StorageService
import de.uksh.medic.cxx2medic.integration.service.resilience.FileReplayService
import de.uksh.medic.cxx2medic.integration.service.resilience.ReplayService
import de.uksh.medic.cxx2medic.util.Identifiers
import de.uksh.medic.cxx2medic.util.dataIsAbsentBecause
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.springframework.integration.context.IntegrationContextUtils
import org.springframework.integration.core.MessageSource
import org.springframework.integration.dsl.BaseIntegrationFlowDefinition.ReplyProducerCleaner
import org.springframework.integration.dsl.integrationFlow
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.support.ErrorMessage
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.support.CronTrigger
import reactor.core.scheduler.Schedulers
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
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
        @Autowired @Qualifier("global:trigger-ctx") triggerContext: UpToDateTriggerContext,
        //@Autowired @Qualifier("replayService") replayService: ReplayService<FileReplayService.Entry>
    ) = integrationFlow(source, { poller { it.trigger(trigger) } }) {
        enrichHeaders {
            header("runTimestamp", triggerContext.currentExecution())
            header("runId", UUID.nameUUIDFromBytes(triggerContext.currentExecution().toString().encodeToByteArray()))
            //header("consentPattern", consentPattern(triggerContext.currentExecution()))
        }
        /*transform<Message<List<Map<String, String?>>>> {
            msg -> replayService.replay(msg) { list, entry ->
            val specimenId = entry.data["specimenId"]
            if (list.any { it["specimenId"] == specimenId }) {
                logger.info("More recent update available for specimen '{}' => Skipping replay", specimenId)
                list
            } else list.toMutableList().apply { add(entry.data) }
        } }*/
        split<List<Map<String, String?>>> { it }
        enrichHeaders {
            headerExpression("specimenId", "payload['specimen_id']")
            headerExpression("patientId", "payload['patient_id']")
            headerExpression("consentId", "payload['consent_id']")
            //header(MessageHeaders.ERROR_CHANNEL, specimenErrorChannel)
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
            val bundle = Bundle().apply { type = Bundle.BundleType.COLLECTION }
            runBlocking {
                msg.payload.forEach { key, value ->
                    launch {
                        if (value != null) fhirService.read(value, key).fold(
                            { it.fold(
                                {
                                    logger.debug(
                                        "Could not find {} instance with ID '{}' [specimenId={}]",
                                        key, value, msg.headers["specimenId"]
                                    )
                                },
                                { r -> bundle.addEntry().resource = r as Resource }
                            ) },
                            { t -> throw t }
                        )
                    }
                }
            }
            return@transform bundle
        }
        channel("cxx-fhir-data")
    }

    @Bean
    fun routeBasedOnCriteria(
        @Autowired evaluationService: FhirPathEvaluationServiceR4
    ) = integrationFlow("cxx-fhir-data") {
        route<Message<Bundle>> { m ->
            val keepChannel = "filtered-fhir-data"
            val deleteChannel = "mark-for-deletion"
            val channel = kotlin.runCatching {
                return@runCatching if (evaluationService.evaluate(m.payload)) keepChannel
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
        transform<Message<Bundle>> { msg ->
            // Due to the previous step the values cannot be null or None so they can be unpacked safely
            val bundle = msg.payload
            val resources = bundle.entry.map { it.resource }
            val specimen = resources.find { it is Specimen }!! as Specimen
            @Suppress("UNCHECKED_CAST")
            val oConsent = resources.find { it is Consent }?.some() ?: None as Option<Consent>
            val oPatient = resources.find { it is Patient }?.some() ?: None as Option<Patient>
            val patientId = oPatient.fold({ msg.headers["patientId"] }, { it.idPart })
            // FIXME: Add proper request type adjustment based on current request type similar to criteria definition
            //        and evaluation
            val requestType = msg.headers["request"] as HTTPVerb

            logger.info("Processing Specimen resource [id=${specimen.idPart}, requestType=$requestType]")

            if (cxxSettings.patientReferenceIdentifier != null) {
                val patient = oPatient.getOrNull()
                evalService.retrieve<Identifier>(patient, cxxSettings.patientReferenceIdentifier).fold(
                    { ids -> when (ids.size) {
                        0 -> {
                            logger.warn("No suitable patient identifier could be found [patientId=${patientId}]. " +
                                    "No reference will be present")
                            specimen.setSubject(
                                Reference().apply { type = "Patient" } dataIsAbsentBecause DataAbsentReason.NOTAPPLICABLE
                            )
                        }
                        else -> {
                            if (ids.size > 1) logger.warn("More than one patient identifier matches " +
                                    "[patientId=${patientId}]. Using first match")
                            specimen.setSubject(Reference().apply {
                                identifier = ids[0]
                                type = "Patient"
                            })
                        }
                    } },
                    { exc ->
                        when (exc) {
                            is IllegalArgumentException -> {
                                logger.warn(
                                    "Failed to retrieve patient identifier [patientId=${patientId}]. Reason: ${exc.message}"
                                )
                                specimen.setSubject(
                                    Reference().apply { type = "Patient" } dataIsAbsentBecause DataAbsentReason.UNKNOWN
                                )
                            }
                            else -> {
                                logger.warn(
                                    "Failed to retrieve patient identifier [patientId=${patientId}]", exc
                                )
                                specimen.setSubject(
                                    Reference().apply { type = "Patient" } dataIsAbsentBecause DataAbsentReason.ERROR
                                )
                            }
                        }
                    }
                )
            }

            specimen.extension.add(Extension().apply {
                url = "https://medic.uksh.de/fhir/StructureDefinition/ext-specimen-consent-identifier"
                when (oConsent) {
                    is None -> this dataIsAbsentBecause DataAbsentReason.UNKNOWN
                    is Some -> setValue(Identifier().apply {
                        system = Identifiers.BIOBANK_CENTRAXX_CONSENT
                        value = oConsent.getOrNull()!!.idPart
                    })
                }
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
        @Autowired bundleSizeLimit: Int,
        //@Autowired bundleErrorChannel: MessageChannel
    ) = integrationFlow("specimen-fhir-data") {
        //resequence {
        //    correlationStrategy(HeaderAttributeCorrelationStrategy(IntegrationMessageHeaderAccessor.CORRELATION_ID))
        //}
        aggregate {
            /*headersFunction { mg ->
                val header = mg.messages.fold(mutableListOf<Map<String, String?>>()) { list, msg ->
                    list.add(mapOf(
                        "specimenId" to msg.headers["specimenId"] as String,
                        "consentId" to msg.headers["consentId"] as String?,
                        "patientId" to msg.headers["patientId"] as String?
                    ))
                    list
                }
                mapOf("originalIds" to header, MessageHeaders.ERROR_CHANNEL to bundleErrorChannel)
            }*/
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
        //@Autowired s3Handler: S3StorageWriterHandler
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

    /*
    @Bean
    fun s3Handler(): S3StorageWriterHandler = S3StorageWriterHandler(s3Service)

    @Bean
    fun handleErrors() = integrationFlow("errorChannel") {
        route<ErrorMessage> { msg -> when (msg.headers.errorChannel) {

        } }
    }

    @Bean
    fun specimenErrorChannel(): MessageChannel = PublishSubscribeChannel()

    @Bean
    fun bundleErrorChannel(): MessageChannel = PublishSubscribeChannel()

    @Bean
    fun handleSpecimenProcessingErrors(
        @Autowired replayService: ReplayService<FileReplayService.Entry>
    ) = integrationFlow("specimenErrorChannel") {
        handle(ReplayingErrorHandler(replayService) { errMsg ->
            val msg = errMsg.originalMessage
            val data = mapOf(
                "specimenId" to msg.headers["specimenId"] as String?,
                "consentId" to msg.headers["consentId"] as String?,
                "patientId" to msg.headers["patientId"] as String?
            )
            listOf(FileReplayService.Entry(data, errMsg.payload.stackTraceToString(), LocalDateTime.now()))
        })
    }

    @Bean
    fun handleBundleProcessingErrors(
        @Autowired replayService: ReplayService<FileReplayService.Entry>
    ) = integrationFlow("bundleErrorChannel") {
        handle(ReplayingErrorHandler(replayService) { errMsg ->
            val msg = errMsg.originalMessage
            (msg.headers["originalIds"] as List<Map<String, String?>>).map { FileReplayService.Entry(
                it, errMsg.payload.stackTraceToString(), LocalDateTime.now()
            ) }
        })
    }
    */
}