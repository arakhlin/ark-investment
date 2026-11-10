# Local demo only: retain the persisted development database, with no credentials in the image.
FROM quay.io/keycloak/keycloak:26.8.2
# Use Keycloak's non-container heap defaults for this small demo, during build and runtime.
ENV JAVA_OPTS_KC_HEAP="-Xms64m -Xmx512m"
RUN /opt/keycloak/bin/kc.sh build --db=dev-file --health-enabled=true
