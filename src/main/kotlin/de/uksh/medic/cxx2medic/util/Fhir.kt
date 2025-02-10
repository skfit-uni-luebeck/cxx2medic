package de.uksh.medic.cxx2medic.util

import org.hl7.fhir.r4.model.ExampleScenario.FHIRResourceType

fun getResourceTypeR4(fhirPath: String): FHIRResourceType =
    FHIRResourceType.fromCode(fhirPath.split(".")[0])