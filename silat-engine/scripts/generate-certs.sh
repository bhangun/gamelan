#!/bin/bash

# Configuration
CERT_DIR="./src/main/resources/certs"
CA_NAME="SilatCA"
SERVER_NAME="localhost"
CLIENT_NAME="silat-client"
PASSWORD="changeit"

mkdir -p $CERT_DIR

echo "Generating CA..."
openssl genrsa -out $CERT_DIR/ca.key 4096
openssl req -x509 -new -nodes -key $CERT_DIR/ca.key -sha256 -days 3650 -out $CERT_DIR/ca.crt -subj "/CN=$CA_NAME"

echo "Generating Server Key and CSR..."
openssl genrsa -out $CERT_DIR/server.key 2048
openssl req -new -key $CERT_DIR/server.key -out $CERT_DIR/server.csr -subj "/CN=$SERVER_NAME"

echo "Signing Server Certificate..."
openssl x509 -req -in $CERT_DIR/server.csr -CA $CERT_DIR/ca.crt -CAkey $CERT_DIR/ca.key -CAcreateserial -out $CERT_DIR/server.crt -days 365 -sha256

echo "Generating Client Key and CSR..."
openssl genrsa -out $CERT_DIR/client.key 2048
openssl req -new -key $CERT_DIR/client.key -out $CERT_DIR/client.csr -subj "/CN=$CLIENT_NAME"

echo "Signing Client Certificate..."
openssl x509 -req -in $CERT_DIR/client.csr -CA $CERT_DIR/ca.crt -CAkey $CERT_DIR/ca.key -CAcreateserial -out $CERT_DIR/client.crt -days 365 -sha256

echo "Converting to PKCS12 for Java KeyStore (Optional but common)..."
openssl pkcs12 -export -in $CERT_DIR/server.crt -inkey $CERT_DIR/server.key -out $CERT_DIR/server.p12 -name server -CAfile $CERT_DIR/ca.crt -caname root -passout pass:$PASSWORD
openssl pkcs12 -export -in $CERT_DIR/client.crt -inkey $CERT_DIR/client.key -out $CERT_DIR/client.p12 -name client -CAfile $CERT_DIR/ca.crt -caname root -passout pass:$PASSWORD

echo "Certificates generated in $CERT_DIR"
