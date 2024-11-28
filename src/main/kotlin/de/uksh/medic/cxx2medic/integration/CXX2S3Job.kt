package de.uksh.medic.cxx2medic.integration

import arrow.core.Option
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import de.uksh.medic.cxx2medic.evaluation.Pattern
import de.uksh.medic.cxx2medic.evaluation.pattern
import de.uksh.medic.cxx2medic.integration.handler.S3StorageWriterHandler
import de.uksh.medic.cxx2medic.integration.scheduling.UpToDateTriggerContext
import de.uksh.medic.cxx2medic.integration.service.CentraXXFhirService
import de.uksh.medic.cxx2medic.integration.service.S3StorageService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.hl7.fhir.r4.model.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
import org.springframework.messaging.support.MessageBuilder
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.util.*

private val logger: Logger = LogManager.getLogger(CXX2S3Job::class.java)

@Configuration
@EnableIntegration
class CXX2S3Job()
{
    @Bean
    fun cxxToS3Flow(
        @Autowired @Qualifier("cxx:msg-source") source: MessageSource<List<Map<String, String?>>>,
        @Autowired @Qualifier("global:trigger") trigger: CronTrigger,
        @Autowired  @Qualifier("global:trigger-ctx") triggerContext: UpToDateTriggerContext,
        @Autowired fhirService: CentraXXFhirService,
        @Autowired s3Service: S3StorageService
    ): IntegrationFlow =
        integrationFlow(source, { poller { it.trigger(trigger) } } )
        {
            enrichHeaders {
                header("consentPattern", consentPattern(triggerContext.currentExecution()))
            }
            split<List<Map<String, String?>>> { it }
            channel("cxx-db-data")
            filter<Map<String, String?>> { row -> null !in row.values }
            transform<Map<String, String>> { m: Map<String, String> ->
                val specimen = fhirService.readSpecimen(m["oid"]!!)
                val patient = fhirService.readPatient(m["patientcontainer"]!!)
                val consent = fhirService.readConsent(m["consent"]!!)
                Triple(specimen, consent, patient)
            }
            channel("cxx-fhir-data")
            filter<Message<Triple<Option<Specimen>, Option<Consent>, Option<Patient>>>> { m ->
                @Suppress("UNCHECKED_CAST")
                val consentPattern = m.headers["consentPattern"]!! as Pattern<Consent>
                val (specimen, consent, patient) = m.payload
                specimen.fold({ false }, { s -> specimenPattern.evaluate(s) })
                        && consent.fold({ false }, { c -> consentPattern.evaluate(c) })
                        && patient.fold({ false }, {true})
            }
            transform<Triple<Option<Specimen>, Option<Consent>, Option<Patient>>> { (specimen, consent, patient) ->
                // Due to the previous step the values cannot be null or None so they can be unpacked safely
                Triple(specimen.getOrNull()!!, consent.getOrNull()!!, patient.getOrNull()!!)
            }
            transform<Triple<Specimen, Consent, Patient>> { (specimen, consent, patient) ->
                logger.debug("Processing Specimen [id=${specimen.id}]")

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

                specimen
            }
            channel("specimen-fhir-data")
            channel("specimen-bundle-data")
            //handle(System.out::println)
            handle(S3StorageWriterHandler(s3Service))
        }

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