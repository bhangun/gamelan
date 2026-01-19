# Gamelan Standalone Runtime Scripts

This directory contains utility scripts for the Gamelan standalone runtime.

## Certificate Generation

The `generate-certs.sh` script creates self-signed certificates for TLS/SSL communication in the standalone runtime.

### Usage

```bash
# Generate certificates
./scripts/generate-certs.sh

# The certificates will be created in the ./certs directory
```

### Generated Certificates

The script creates the following certificates:

- **CA Certificate**: Root certificate authority for signing other certificates
- **Server Certificate**: For securing HTTP/gRPC server connections
- **Client Certificate**: For client authentication (if needed)
- **Keystores**: PKCS#12 and JKS format keystores for Java applications
- **Truststore**: Contains the CA certificate for trust verification

### Security Notes

- These certificates are for development and testing only
- For production, use certificates from a trusted Certificate Authority
- The default password for keystores is `changeit`
- Keep private keys secure and never commit them to version control

## Other Scripts

Additional utility scripts may be added here for various operational tasks.