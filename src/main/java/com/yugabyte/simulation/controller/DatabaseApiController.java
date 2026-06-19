package com.yugabyte.simulation.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yugabyte.simulation.dao.api.CertificateInfoResponse;
import com.yugabyte.simulation.dao.api.DatabaseConnectionRequest;
import com.yugabyte.simulation.dao.api.DatabaseConnectionResponse;
import com.yugabyte.simulation.dao.api.UploadCertificateRequest;
import com.yugabyte.simulation.service.CertificateStorageService;
import com.yugabyte.simulation.service.DatabaseConnectionService;

@RestController
@RequestMapping("/api/v1/database")
public class DatabaseApiController {
    @Autowired
    private CertificateStorageService certificateStorageService;

    @Autowired
    private DatabaseConnectionService databaseConnectionService;

    @PostMapping("/certificates")
    public CertificateInfoResponse uploadCertificate(@RequestBody UploadCertificateRequest request) throws IOException {
        return certificateStorageService.store(request.getCertId(), request.getCertificatePem());
    }

    @PostMapping("/certificates/upload")
    public CertificateInfoResponse uploadCertificateFile(@RequestParam("certId") String certId,
            @RequestParam("file") MultipartFile file) throws IOException {
        String pem = new String(file.getBytes(), StandardCharsets.UTF_8);
        return certificateStorageService.store(certId, pem);
    }

    @GetMapping("/certificates")
    public List<CertificateInfoResponse> listCertificates() throws IOException {
        return certificateStorageService.listCertificates();
    }

    @DeleteMapping("/certificates/{certId}")
    public void deleteCertificate(@PathVariable String certId) throws IOException {
        certificateStorageService.deleteCertificate(certId);
    }

    @PostMapping("/connection")
    public DatabaseConnectionResponse configureConnection(@RequestBody DatabaseConnectionRequest request) throws Exception {
        return databaseConnectionService.reconfigure(request);
    }

    @GetMapping("/connection")
    public DatabaseConnectionResponse getConnection() {
        return databaseConnectionService.getCurrentConfiguration();
    }
}
