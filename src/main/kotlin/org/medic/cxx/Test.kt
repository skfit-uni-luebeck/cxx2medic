package org.medic.cxx

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Specimen
import org.medic.cxx.evaluation.b
import org.medic.cxx.evaluation.pattern

fun main(args: Array<String>) {

    val specimenPattern = pattern<Specimen> {
        // Exclude aliquot groups as they do not represent real physical samples
        noneOf({ extension }) {
            pathOfType<Coding>({ value }) {
                check ("system") { system == "https://fhir.centraxx.de/system/sampleCategory" }
            }
        }
    }

    val specimen = FhirContext.forR4().newJsonParser().parseResource(specimenStr) as Specimen

    val b1 = b<Specimen, Coding>({ it.extension[0].value }, null)
    println("b1: ${b1.invoke(specimen)}")

    specimenPattern.evaluate(specimen)

}

val specimenStr ="""
    {
      "resourceType": "Specimen",
      "id": "104742",
      "extension": [ {
        "url": "https://fhir.centraxx.de/extension/sample/repositionDate",
        "valueDateTime": "2023-06-05T11:02:18+02:00"
      } ],
      "identifier": [ {
        "type": {
          "coding": [ {
            "system": "https://fhir.centraxx.de/system/idContainerType",
            "code": "SAMPLEID",
            "display": "Proben ID"
          } ]
        },
        "value": "102394"
      } ],
      "type": {
        "coding": [ {
          "system": "https://fhir.centraxx.de/system/sample/sampleType",
          "code": "ST_F_BLD_VOLLBLUT",
          "display": "Vollblut (BLD)"
        }, {
          "system": "https://doi.org/10.1089/bio.2017.0109",
          "code": "BLD",
          "display": "Vollblut (BLD)"
        } ]
      },
      "subject": {
        "reference": "Patient/157509"
      },
      "receivedTime": "2023-06-05T10:58:03+02:00",
      "collection": {
        "collectedDateTime": "2023-06-05",
        "quantity": {
          "value": 1.0000,
          "unit": "PC"
        }
      },
      "processing": [ {
        "procedure": {
          "coding": [ {
            "system": "urn:centraxx",
            "code": "410min1500g",
            "display": "4 °C 10 min 1500g mit Bremse"
          }, {
            "system": "https://doi.org/10.1089/bio.2017.0109",
            "code": "D",
            "display": "2 °C bis 10 °C 10 - 15 min < 3000 g mit Bremse (D)"
          } ]
        }
      } ],
      "container": [ {
        "identifier": [ {
          "system": "https://fhir.centraxx.de/system/sample/sampleReceptacle",
          "value": "SC_Y_OPC"
        } ],
        "description": "Originaler Primärcontainer (Y)",
        "capacity": {
          "unit": "X"
        },
        "specimenQuantity": {
          "value": 0.0000,
          "unit": "PC"
        }
      } ]
    }
""".trimIndent()