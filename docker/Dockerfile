FROM alpine/java:17-jdk
LABEL authors="pbehrend"

WORKDIR /app

COPY build/libs/cxx2medic.jar .
COPY config/logback-spring.xml config/logback-spring.xml

RUN mkdir logs
RUN chmod -R 666 logs

ENTRYPOINT java -jar cxx2medic.jar