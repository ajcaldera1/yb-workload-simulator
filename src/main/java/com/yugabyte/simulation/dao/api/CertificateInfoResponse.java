package com.yugabyte.simulation.dao.api;

public class CertificateInfoResponse {
    private String certId;
    private long uploadedAtMs;

    public CertificateInfoResponse() {
    }

    public CertificateInfoResponse(String certId, long uploadedAtMs) {
        this.certId = certId;
        this.uploadedAtMs = uploadedAtMs;
    }

    public String getCertId() {
        return certId;
    }

    public void setCertId(String certId) {
        this.certId = certId;
    }

    public long getUploadedAtMs() {
        return uploadedAtMs;
    }

    public void setUploadedAtMs(long uploadedAtMs) {
        this.uploadedAtMs = uploadedAtMs;
    }
}
