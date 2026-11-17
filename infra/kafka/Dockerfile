FROM apache/kafka:4.1.2@sha256:5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e

USER root
RUN apk add --no-cache kcat=1.7.1-r2 librdkafka=2.12.1-r0
USER 1000:1000
