#!/bin/bash

healthcheck="curl --silent --fail http://localhost:8080/health"
limit=10
counter=0

until eval "$healthcheck"
do
  if [[ $counter -ge $limit ]]; then
    echo "Healthcheck limit reached"
    exit 1
  fi
  counter=$((counter+1))
  sleep 10s
done

echo "Uploading bundle"
curl -H "Content-Type: application/json" -X POST -d @/app/init/bundle.json http://localhost:8080/fhir