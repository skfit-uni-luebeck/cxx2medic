# CXX2MEDIC

This project contains the components necessary to extract specimen data from **CentraXX**'s FHIR facade/interface,
transform, and publish it in the **openEHR repository** for later use inside the **Medical Data Integration Center** (MeDIC)
of the UKSH.

## Components

### CXX2NIFI

Retrieves data from the CentraXX FHIR facade and publishes it to a dedicated NIFI endpoint for later processing. The job
extracts FHIR Specimen resources along with the associated FHIR Patient and Consent resources. Retrieved FHIR Specimen
resources are filtered by type (to exclude data not representing real sample material) and associated FHIR Consent 
resource is checked for the right policies in order to allow further processing of the resources. The job adds
references to both the patient and consent associated with a given specimen and posts the resulting resource to the NIFI
endpoint.

#### Environment Variables

| Name              | Type | Description                                                                                 | 
|-------------------|------|---------------------------------------------------------------------------------------------| 
| QUERY_RESULT_FILE | Path | Location of the input query results from the CentraXX database with new specimen to process |
| FHIR_SERVER_URL   | URL  | URL of the CentraXX FHIR facade to retrieve FHIR resources from                             |
| NIFI_TARGET_URL   | URL  | NIFI endpoint to post the resulting FHIR Specimen resources to                              |

### Kakfa2openEHR

...