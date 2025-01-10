package de.uksh.medic.cxx2medic.util

fun getResourceType(fhirPath: String): String =
    fhirPath.split(".")[0]