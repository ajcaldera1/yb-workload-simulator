package com.yugabyte.simulation.exception;

public class CertificateNotFoundException extends RuntimeException {
    private final String certId;

    public CertificateNotFoundException(String certId) {
        super("Certificate not found: " + certId);
        this.certId = certId;
    }

    public String getCertId() {
        return certId;
    }
}
