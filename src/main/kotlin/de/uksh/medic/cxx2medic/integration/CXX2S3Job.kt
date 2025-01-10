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
        channel("cxx-db-data")
    }

    @Bean
    fun readCentraxxFhirFacade(
        @Autowired fhirService: CentraXXFhirService,
    ) = integrationFlow("cxx-db-data") {
        transform<Message<Map<String, String>>> { msg: Message<Map<String, String>> ->
            val m = msg.payload
            val specimen = fhirService.readSpecimen(m["oid"]!!)
            val patient = fhirService.readPatient(m["patientcontainer"]!!)
            val consent = fhirService.readConsent(m["consent"]!!)
            val changeType = m["change_kind"]!!
            MessageBuilder.withPayload(Triple(specimen, consent, patient))
                .copyHeaders(msg.headers)
                .setHeader("request", when (changeType) {
                    "created" -> HTTPVerb.POST
                    "updated" -> HTTPVerb.PUT
                    "deleted" -> HTTPVerb.DELETE
                    else -> throw UnknownChangeTypeException(changeType)
                })
                .build()
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
            evaluationService.evaluate(list)
        }
        transform<Triple<Option<Specimen>, Option<Consent>, Option<Patient>>> { (specimen, consent, patient) ->
            // Due to the previous step the values cannot be null or None so they can be unpacked safely
            Triple(specimen.getOrNull()!!, consent.getOrNull()!!, patient.getOrNull()!!)
        }
        channel("filtered-fhir-data")
    }

    @Bean
    fun enrichSpecimen(
        @Autowired cxxSettings: CentraXXSettings
    ) = integrationFlow("filtered-fhir-data") {
        transform<Message<Triple<Specimen, Consent, Patient>>> { msg ->
            val (specimen, consent, patient) = msg.payload

            logger.info("Processing Specimen [id=${specimen.idPart}]")

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
                    value = consent.id
                })
            })
            specimen.extension.add(Extension().apply {
                url = "https://www.medizininformatik-initiative.de/fhir/ext/modul-biobank/StructureDefinition/VerwaltendeOrganisation"
                setValue(cxxSettings.managingOrg)
            })

            BundleEntryComponent().apply {
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
            headerExpression(S3StorageWriterHandler.OBJECT_NAME_HEADER, "payload.id + \".ndjson\"")
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
            header(MessageHeaders.CONTENT_TYPE, "application/fhir+ndjson")
        }
        channel(PublishSubscribeChannel().apply {
            beanName = "specimen-bundle-raw"
            subscribe(S3StorageWriterHandler(s3Service))
        })
    }

    companion object
    {
        private val specimenPattern: Pattern<Specimen> =
            pattern {
                // Exclude aliquot groups as they do not represent real physical samples
                noneOf({ extension }) {
                    check { url == "https://fhir.centraxx.de/extension/sampleCategory" }
                    pathOfType<Coding>({ value }) {
                        check { system == "https://fhir.centraxx.de/system/sampleCategory" }
                        check { code == "ALIQUOTGROUP" }
                        check { display == "Aliquotgruppe" }
                    }
                }
                path({ collection.quantity }) {
                    check { value > BigDecimal.ZERO }
                }
            }

        private fun consentPattern(current: Instant): Pattern<Consent> =
            pattern {
                // Active consent resource
                check { status == Consent.ConsentState.ACTIVE }
                path({ provision }) {
                    // Provision validity has to include current point in time
                    path({ period }) {
                        check { start < Date.from(current) }
                        checkIfExists { end > Date.from(current) }
                    }
                    // Purpose reflecting consent type required for export
                    anyOf({ purpose }) {
                        check { system == "https://fhir.centraxx.de/system/consent/type" }
                        check { code == "2IC" }
                        check { display == "2. IC Biomaterial ICB-L" }
                    }
                }
            }

        private val identifierPattern: Pattern<Identifier> =
            pattern {
                anyOf({ type.coding }) {
                    check { system == "https://fhir.centraxx.de/system/idContainerType" }
                    check { code == "KIS-ID" }
                    check { display == "ORBIS-ID" }
                }
            }
    }
}