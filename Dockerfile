FROM openjdk:17-alpine

ENV QUERY_RESULT_FILE="/opt/app/cxx2nifi/input/result.csv"
ENV FHIR_SERVER_URL="http://localhost:8080/fhir"
ENV NIFI_TARGET_URL="http://locahost:8081/nifi/fhir"

WORKDIR /opt/app/cxx2nifi

COPY ./pom.xml ./pom.xml
COPY ./.mvn/wrapper/maven-wrapper.properties ./.mvn/wrapper/maven-wrapper.properties
COPY ./mvnw ./mvnw
COPY ./src ./src

# Build JAR
RUN ./mvnw clean compile package
RUN mv ./target/cxx2nifi.jar .

# Cleanup
RUN rm -r ./target
RUN rm -r ./src
RUN rm -r ./.mvn
RUN rm ./pom.xml
RUN rm ./mvnw

RUN mkdir input

ENTRYPOINT java -jar ./cxx2nifi.jar -i ${QUERY_RESULT_FILE} -s ${FHIR_SERVER_URL} -t ${NIFI_TARGET_URL}