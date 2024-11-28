package de.uksh.medic.cxx2medic.integration.service

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Consent
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Specimen
import de.uksh.medic.cxx2medic.config.FhirSettings
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
class CentraXXFhirService(
    @Autowired private val fhirContext: FhirContext,
    @Autowired @Qualifier("cxx:fhir-settings") settings: FhirSettings
)
{
    private val url: String = settings.url
    private val auth: Pair<String, String> = settings.username to settings.password
    private val client: IGenericClient = fhirContext.newRestfulGenericClient(url)

    fun read(id: String, type: String): Option<IBaseResource> =
        kotlin.runCatching { client.read().resource(type).withId(id).execute() }
            .fold({ r -> Some(r) }, { _ -> None })

    @Cacheable("fhir-patient")
    fun readPatient(id: String): Option<Patient> =
        read(id, "Patient") as Option<Patient>

    @Cacheable("fhir-consent")
    fun readConsent(id: String): Option<Consent> =
        read(id, "Consent") as Option<Consent>

    fun readSpecimen(id: String): Option<Specimen> =
        read(id, "Specimen") as Option<Specimen>
}