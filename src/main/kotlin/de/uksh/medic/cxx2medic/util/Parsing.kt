package de.uksh.medic.cxx2medic.util

import kotlinx.cli.ParsingException
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import java.net.URI

fun parseIdentifierToken(str: String): Result<Identifier> = runCatching {
    val split = str.split("|")
    return if (split.size != 2)
        throw ParsingException("Identifier token string does adhere to pattern <system>|<value> [str=$str]")
    else if (split[0].isEmpty())
        throw ParsingException("Identifier token string has no system part [str=$str]")
    else if (split[1].isEmpty())
        throw ParsingException("Identifier token string has no value part [str=$str]")
    else {
        Result.success(Identifier().apply {
            system = URI.create(split[0]).toString()
            value = split[1]
        })
    }
}