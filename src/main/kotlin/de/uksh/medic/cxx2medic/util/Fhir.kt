package de.uksh.medic.cxx2medic.util

import ca.uhn.fhir.model.api.IExtension
import org.hl7.fhir.instance.model.api.IBaseExtension
import org.hl7.fhir.instance.model.api.IBaseHasExtensions
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.Enumerations.DataAbsentReason
import org.hl7.fhir.r4.model.ExampleScenario.FHIRResourceType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.StringType

fun getResourceTypeR4(fhirPath: String): FHIRResourceType =
    FHIRResourceType.fromCode(fhirPath.split(".")[0])

infix fun <T: IBaseHasExtensions> T.dataIsAbsentBecause(reason: DataAbsentReason): T
{
    addExtension().apply {
        url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
        setValue(CodeType(reason.toCode()))
    }
    return this
}

fun <T: IBaseHasExtensions> T.dataIsAbsent() = dataIsAbsentBecause(DataAbsentReason.UNKNOWN)