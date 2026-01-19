#!/bin/bash

# Script to generate certificates for Gamelan standalone runtime
# This script creates self-signed certificates for TLS/SSL communication

set -e

# Configuration
CERTS_DIR="./certs"
KEYSTORE_DIR="$CERTS_DIR/keystore"
TRUSTSTORE_DIR="$CERTS_DIR/truststore"
CA_CERT="$CERTS_DIR/ca.crt"
CA_KEY="$CERTS_DIR/ca.key"
SERVER_CERT="$CERTS_DIR/server.crt"
SERVER_KEY="$CERTS_DIR/server.key"
CLIENT_CERT="$CERTS_DIR/client.crt"
CLIENT_KEY="$CERTS_DIR/client.key"
SERVER_P12="$KEYSTORE_DIR/server.p12"
CLIENT_P12="$KEYSTORE_DIR/client.p12"
SERVER_JKS="$KEYSTORE_DIR/server.jks"
CLIENT_JKS="$KEYSTORE_DIR/client.jks"
TRUSTSTORE_JKS="$TRUSTSTORE_DIR/truststore.jks"

# Create directories
mkdir -p "$KEYSTORE_DIR" "$TRUSTSTORE_DIR"

# Generate CA (Certificate Authority)
echo "Generating Certificate Authority..."
openssl req -new -newkey rsa:4096 -days 365 -nodes -x509 \
  -subj "/C=ID/ST=Jakarta/L=Jakarta/O=Kayys/OU=Gamelan/CN=gamelan-ca" \
  -keyout "$CA_KEY" -out "$CA_CERT"

# Generate server certificate
echo "Generating server certificate..."
openssl req -new -newkey rsa:2048 -nodes -keyout "$SERVER_KEY" \
  -subj "/C=ID/ST=Jakarta/L=Jakarta/O=Kayys/OU=Gamelan/CN=localhost" \
  -out /tmp/server.req

# Sign server certificate with CA
echo "Signing server certificate..."
openssl x509 -req -in /tmp/server.req -CA "$CA_CERT" -CAkey "$CA_KEY" \
  -CAcreateserial -out "$SERVER_CERT" -days 365 \
  -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:0.0.0.0")

# Generate client certificate
echo "Generating client certificate..."
openssl req -new -newkey rsa:2048 -nodes -keyout "$CLIENT_KEY" \
  -subj "/C=ID/ST=Jakarta/L=Jakarta/O=Kayys/OU=Gamelan/CN=gamelan-client" \
  -out /tmp/client.req

# Sign client certificate with CA
echo "Signing client certificate..."
openssl x509 -req -in /tmp/client.req -CA "$CA_CERT" -CAkey "$CA_KEY" \
  -CAcreateserial -out "$CLIENT_CERT" -days 365

# Create PKCS#12 keystores
echo "Creating server keystore (PKCS#12)..."
openssl pkcs12 -export -inkey "$SERVER_KEY" -in "$SERVER_CERT" \
  -certfile "$CA_CERT" -out "$SERVER_P12" -passout pass:changeit

echo "Creating client keystore (PKCS#12)..."
openssl pkcs12 -export -inkey "$CLIENT_KEY" -in "$CLIENT_CERT" \
  -certfile "$CA_CERT" -out "$CLIENT_P12" -passout pass:changeit

# Create JKS keystores
echo "Creating server keystore (JKS)..."
keytool -importkeystore -srckeystore "$SERVER_P12" -srcstoretype PKCS12 \
  -destkeystore "$SERVER_JKS" -deststoretype JKS -srcstorepass changeit \
  -deststorepass changeit -noprompt

echo "Creating client keystore (JKS)..."
keytool -importkeystore -srckeystore "$CLIENT_P12" -srcstoretype PKCS12 \
  -destkeystore "$CLIENT_JKS" -deststoretype JKS -srcstorepass changeit \
  -deststorepass changeit -noprompt

# Create truststore with CA certificate
echo "Creating truststore..."
keytool -import -alias gamelan-ca -file "$CA_CERT" -keystore "$TRUSTSTORE_JKS" \
  -storepass changeit -noprompt

# Clean up temporary files
rm -f /tmp/server.req /tmp/client.req /tmp/ca.srl

echo "Certificates generated successfully in $CERTS_DIR/"
echo ""
echo "Generated files:"
echo "  - $CA_CERT: Certificate Authority certificate"
echo "  - $CA_KEY: Certificate Authority private key"
echo "  - $SERVER_CERT: Server certificate"
echo "  - $SERVER_KEY: Server private key"
echo "  - $CLIENT_CERT: Client certificate"
echo "  - $CLIENT_KEY: Client private key"
echo "  - $SERVER_P12: Server PKCS#12 keystore"
echo "  - $CLIENT_P12: Client PKCS#12 keystore"
echo "  - $SERVER_JKS: Server JKS keystore"
echo "  - $CLIENT_JKS: Client JKS keystore"
echo "  - $TRUSTSTORE_JKS: Truststore with CA certificate"
echo ""
echo "Note: These certificates are for development/testing only."
echo "For production, use proper certificates from a trusted CA."