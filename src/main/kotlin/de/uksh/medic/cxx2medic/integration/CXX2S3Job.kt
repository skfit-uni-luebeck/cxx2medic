package de.uksh.medic.cxx2medic.integration

import arrow.core.Option
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import de.uksh.medic.cxx2medic.config.CentraXXSettings
import de.uksh.medic.cxx2medic.evaluation.Pattern
import de.uksh.medic.cxx2medic.evaluation.pattern
import de.uksh.medic.cxx2medic.exception.UnknownChangeTypeException
import de.uksh.medic.cxx2medic.integration.aggregator.strategy.SequenceAwareMessageCountReleaseStrategy
import de.uksh.medic.cxx2medic.integration.handler.S3StorageWriterHandler
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.CentraXXFhirService
import de.uksh.medic.cxx2medic.integration.service.FhirPathEvaluationServiceR4
import de.uksh.medic.cxx2medic.integration.service.S3StorageService
import de.uksh.medic.cxx2medic.util.Identifiers
import okio.internal.commonAsUtf8ToByteArray
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.Bundle.HTTPVerb
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.integration.aggregator.MessageCountReleaseStrategy
import org.springframework.integration.aggregator.ReleaseStrategy
import org.springframework.integration.aggregator.SequenceSizeReleaseStrategy
import org.springframework.integration.aggregator.SimpleSequenceSizeReleaseStrategy
import org.springframework.integration.annotation.MessageEndpoint
import org.springframework.integration.annotation.ServiceActivator
import org.springframework.integration.channel.PublishSubscribeChannel
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.core.MessageSource
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.dsl.StandardIntegrationFlow
import org.springframework.integration.dsl.integrationFlow
import org.springframework.integration.handler.LoggingHandler
import org.springframework.integration.router.HeaderValueRouter
import org.springframework.integration.transformer.HeaderEnricher
import org.springframework.messaging.Message
import org.springframework.messaging.MessageHandler
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
    @Autowired private val s3Service: S3StorageService
)
{
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
        filter<Map<String, String?>> { row -> null !in row.values }
        transform<Message<Map<String, String?>>> { msg: Message<Map<String, String?>> ->
            val m = msg.payload
            MessageBuilder.withPayload(Triple(m["specimen_id"]!!, m["patient_id"]!!, m["consent_id"]!!))
                .copyHeaders(msg.headers)
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
        route<Message<Triple<String, String, String>>> { m ->
            when (val verb = m.headers["request"] as HTTPVerb) {
                HTTPVerb.POST, HTTPVerb.PUT -> "cxx-db-data-query-facade"
                HTTPVerb.DELETE -> "cxx-db-data-skip-facade"
                else -> {
                    logger.warn("Unsupported request method (change kind) $verb => Discarding")
                    "nullChannel"
                }
            }
        }
    }

    @Bean
    fun readCentraxxFhirFacade(
        @Autowired fhirService: CentraXXFhirService,
    ) = integrationFlow("cxx-db-data-query-facade") {
        transform<Message<Triple<String, String, String>>> { msg: Message<Triple<String, String, String>> ->
            val m = msg.payload
            val specimen = fhirService.readSpecimen(m.first)
            val patient = fhirService.readPatient(m.second)
            val consent = fhirService.readConsent(m.third)
            Triple(specimen, patient, consent)
        }
        channel("cxx-fhir-data")
    }

    @Bean
    fun filterByCriteria(
        @Autowired evaluationService: FhirPathEvaluationServiceR4
    ) = integrationFlow("cxx-fhir-data") {
        filter<Message<Triple<Option<Specimen>, Option<Consent>, Option<Patient>>>> { m ->
            val list = m.payload.toList().map { if (it.isSome()) it.getOrNull()!! else return@filter false }
            logger.info("Evaluating Specimen [id=${list[0].idPart}]")
            kotlin.runCatching { evaluationService.evaluate(list) }.getOrElse { exc ->
                when (exc) {
                    is NoSuchElementException -> logger.debug("${exc.message} => Excluding")
                    else -> logger.warn("Failed to evaluate criteria => Excluding", exc)
                }
                false
            }
        }
        transform<Triple<Option<Specimen>, Option<Consent>, Option<Patient>>> { (specimen, consent, patient) ->
            // Due to the previous step the values cannot be null or None so they can be unpacked safely
            Triple(specimen.getOrNull()!!, consent.getOrNull()!!, patient.getOrNull()!!)
        }
        channel("filtered-fhir-data")
    }

    @Bean
    fun enrichSpecimen(
        @Autowired cxxSettings: CentraXXSettings,
        @Autowired identifierPattern: Pattern<Identifier>
    ) = integrationFlow("filtered-fhir-data") {
        filter<Message<Triple<Specimen, Consent, Patient>>> { msg ->
            val (_, _, patient) = msg.payload
            val result = patient.identifier.any { identifierPattern.evaluate(it) }
            if (!result) logger.debug("Patient resource has no identifier with configured type code " +
                    "[id=${patient.idPart}] => Excluding")
            result
        }
        transform<Message<Triple<Specimen, Consent, Patient>>> { msg ->
            val (specimen, consent, patient) = msg.payload
            val requestType = msg.headers["request"] as HTTPVerb

            logger.info("Processing Specimen resource [id=${specimen.idPart}, changeType=$requestType]")

            specimen.subject.apply {
                identifier = patient.identifier.filter { identifierPattern.evaluate(it) }[0]
                type = "Patient"
            }

            specimen.extension.add(Extension().apply {
                url = "https://medic.uksh.de/fhir/StructureDefinition/ext-specimen-consent-identifier"
                setValue(Identifier().apply {
                    system = Identifiers.BIOBANK_CENTRAXX_CONSENT
                    value = consent.idPart
                })
            })
            specimen.extension.add(Extension().apply {
                url = "https://www.medizininformatik-initiative.de/fhir/ext/modul-biobank/StructureDefinition/VerwaltendeOrganisation"
                setValue(cxxSettings.managingOrg)
            })

            BundleEntryComponent().apply {
                fullUrl = "${Identifiers.BIOBANK_CENTRAXX_SPECIMEN_OID}/${specimen.idPart}"
                resource = specimen
                request.method = msg.headers["request"] as HTTPVerb
                request.url = when (request.method) {
                    HTTPVerb.PUT -> "{protocol}://{openehr_base_url}/rest/v1/ehr/{ehr_id}/composition/{uid_based_id}"
                    else -> "{protocol}://{openehr_base_url}/rest/v1/ehr/{ehr_id}/composition"
                }
            }
        }
        channel("specimen-fhir-data")
    }

    @Bean
    fun processChangesWithoutContent() = integrationFlow("cxx-db-data-skip-facade") {
        transform<Message<Triple<String, String, String>>> { msg ->
            val specimenId = msg.payload.first
            val requestType = msg.headers["request"] as HTTPVerb
            logger.info("Processing specimen entry [id=$specimenId, changeType=$requestType]")
            BundleEntryComponent().apply {
                fullUrl = "${Identifiers.BIOBANK_CENTRAXX_SPECIMEN_OID}/${specimenId}"
                request.method = msg.headers["request"] as HTTPVerb
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
        aggregate { releaseStrategy(SequenceAwareMessageCountReleaseStrategy(bundleSizeLimit)) }
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

    @Bean(name = ["identifierPattern"])
    fun identifierPattern(@Autowired cxxSettings: CentraXXSettings): Pattern<Identifier> =
        pattern {
            anyOf({ type.coding }) {
                check { system == "https://fhir.centraxx.de/system/idContainerType" }
                check { code == cxxSettings.patientIdentifierTypeCode }
            }
        }
}