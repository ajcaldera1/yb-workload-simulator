package com.yugabyte.simulation.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yugabyte.simulation.dao.api.CertificateInfoResponse;
import com.yugabyte.simulation.exception.CertificateNotFoundException;
import com.yugabyte.simulation.exception.ParameterValidationException;
import com.yugabyte.simulation.util.SSLContextUtility;

@Service
public class CertificateStorageService {
    private final Path certDirectory;
    private final Map<String, Long> uploadTimestamps = new ConcurrentHashMap<String, Long>();

    public CertificateStorageService(
            @Value("${yb-workload-simulator.cert-directory:#{systemProperties['java.io.tmpdir'] + '/yb-workload-simulator/certs'}}") String certDirectoryPath) {
        this.certDirectory = Paths.get(certDirectoryPath).toAbsolutePath().normalize();
    }

    public CertificateInfoResponse store(String certId, String certificatePem) throws IOException {
        String safeCertId = sanitizeCertId(certId);
        try {
            SSLContextUtility.validateCertificatePem(certificatePem);
        } catch (IllegalArgumentException e) {
            throw new ParameterValidationException(e.getMessage());
        }

        Files.createDirectories(certDirectory);
        Path certPath = resolveCertPath(safeCertId);
        Files.write(certPath, certificatePem.getBytes(StandardCharsets.UTF_8));

        long uploadedAt = System.currentTimeMillis();
        uploadTimestamps.put(safeCertId, uploadedAt);
        return new CertificateInfoResponse(safeCertId, uploadedAt);
    }

    public List<CertificateInfoResponse> listCertificates() throws IOException {
        Files.createDirectories(certDirectory);
        List<CertificateInfoResponse> certs = new ArrayList<CertificateInfoResponse>();
        if (!Files.isDirectory(certDirectory)) {
            return certs;
        }
        for (Path path : Files.newDirectoryStream(certDirectory, "*.crt")) {
            String certId = stripExtension(path.getFileName().toString());
            Long uploadedAt = uploadTimestamps.get(certId);
            if (uploadedAt == null) {
                uploadedAt = Files.getLastModifiedTime(path).toMillis();
            }
            certs.add(new CertificateInfoResponse(certId, uploadedAt));
        }
        return certs;
    }

    public String getCertificatePath(String certId) throws IOException {
        String safeCertId = sanitizeCertId(certId);
        Path certPath = resolveCertPath(safeCertId);
        if (!Files.isRegularFile(certPath)) {
            throw new CertificateNotFoundException(safeCertId);
        }
        return certPath.toString();
    }

    public void deleteCertificate(String certId) throws IOException {
        String safeCertId = sanitizeCertId(certId);
        Path certPath = resolveCertPath(safeCertId);
        if (!Files.deleteIfExists(certPath)) {
            throw new CertificateNotFoundException(safeCertId);
        }
        uploadTimestamps.remove(safeCertId);
    }

    private Path resolveCertPath(String certId) {
        Path certPath = certDirectory.resolve(certId + ".crt").normalize();
        if (!certPath.startsWith(certDirectory)) {
            throw new ParameterValidationException("Invalid certificate id");
        }
        return certPath;
    }

    private static String sanitizeCertId(String certId) {
        if (certId == null || certId.trim().isEmpty()) {
            throw new ParameterValidationException("certId is required");
        }
        String trimmed = certId.trim();
        if (!trimmed.matches("[A-Za-z0-9._-]+")) {
            throw new ParameterValidationException("certId may only contain letters, numbers, dots, dashes, and underscores");
        }
        return trimmed;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
