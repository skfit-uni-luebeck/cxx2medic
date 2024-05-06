package org.medic.cxx

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import org.hl7.fhir.r4.model.Specimen
import kotlin.io.path.Path
import org.medic.cxx.util.csv.iterateByLine
import org.medic.cxx.util.csv.streamByLine

private val fhirCtx = FhirContext.forR4()

fun main(args: Array<String>)
{

    val parser = ArgParser("argParser")
    val input by parser.option(ArgType.String, "input", "i", description = "Input file").required()
    val output by parser.option(ArgType.String, "output", "o", description = "Output file").required()
    val serverUrl by parser.option(ArgType.String, "server-url", "s", description = "FHIR server URL").required()
    parser.parse(args)

    val queryResultFilePath = Path(input)
    val fhirClient = fhirCtx.newRestfulGenericClient(serverUrl)

    streamByLine(queryResultFilePath).map { it.joinToString(", ") }.forEach{ println(it) }

}

suspend fun requestSpecimen(oid: String, client: IGenericClient): Specimen
{
    TODO()
}