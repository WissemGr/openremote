FROM quay.io/keycloak/keycloak:22.0

# Copy any custom themes if they exist
COPY deployment/keycloak/themes /opt/keycloak/themes

# Set the entrypoint
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
