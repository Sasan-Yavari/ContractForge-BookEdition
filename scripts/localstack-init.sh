#!/bin/sh
set -eu

endpoint=http://localstack:4566
aws --endpoint-url "$endpoint" s3api create-bucket --bucket contractforge-local
aws --endpoint-url "$endpoint" s3api put-bucket-cors --bucket contractforge-local \
  --cors-configuration '{"CORSRules":[{"AllowedOrigins":["http://localhost:8080"],"AllowedMethods":["PUT"],"AllowedHeaders":["*"]}]}'

for queue in processing processing-dlq document-deletion accounting accounting-dlq notification notification-dlq account-deletion account-deletion-dlq; do
  aws --endpoint-url "$endpoint" sqs create-queue --queue-name "contractforge-local-$queue" >/dev/null
done
