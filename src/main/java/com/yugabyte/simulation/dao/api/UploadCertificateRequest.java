package com.yugabyte.simulation.dao.api;

public class UploadCertificateRequest {
    private String certId;
    private String certificatePem;

    public String getCertId() {
        return certId;
    }

    public void setCertId(String certId) {
        this.certId = certId;
    }

    public String getCertificatePem() {
        return certificatePem;
    }

    public void setCertificatePem(String certificatePem) {
        this.certificatePem = certificatePem;
    }
}
