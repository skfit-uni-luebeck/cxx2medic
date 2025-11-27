#!/bin/bash

healthcheck="/opt/mssql-tools18/bin/sqlcmd -S localhost -C -U sa -P ${MSSQL_SA_PASSWORD} -Q \"SELECT 1\" -b -o /dev/null"
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

echo "Executing SQL init script"
/opt/mssql-tools18/bin/sqlcmd -S localhost -C -U sa -P "${MSSQL_SA_PASSWORD}" -d tempdb -i /var/opt/mssql/init/init-database.sql -b -e
