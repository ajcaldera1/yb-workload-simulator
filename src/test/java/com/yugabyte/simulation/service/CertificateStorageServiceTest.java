package com.yugabyte.simulation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.yugabyte.simulation.exception.CertificateNotFoundException;
import com.yugabyte.simulation.exception.ParameterValidationException;

class CertificateStorageServiceTest {
    private static final String VALID_PEM =
            "-----BEGIN CERTIFICATE-----\n"
                    + "MIIDCTCCAfGgAwIBAgIUD3aliaqdfUZSg7HjcIdykqJXeCgwDQYJKoZIhvcNAQEL\n"
                    + "BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDYxOTE4MTMwMVoXDTI3MDYx\n"
                    + "OTE4MTMwMVowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF\n"
                    + "AAOCAQ8AMIIBCgKCAQEAytuYZUQ6VJG5o9jRk4bQyaTsHb+8qHlFwAwuCC2ZPn0K\n"
                    + "i5FMFVf0Nz4WnJZAg5le2ktT/JRacv81/eQ26+Lm7H4arBVgUAMM9oQoa5+Cqq2R\n"
                    + "ndXUI9rWYkSQD+DEL9s/sgiUyB44+D1+T+rTvYiPz6Qzwy74ihYZQlwzfjRgbHUN\n"
                    + "sfk72uojxKh3cH1eIfQLldn6nLbYU/JAUlcjAMxl9Q0E55AzruJ6HkLT57B1QOLo\n"
                    + "lbbfdUIXt8D51hRMq5kiI8piNSj+Ofci8+ar8uVLnckS8pj7J9vSo1Rfio1vKX4f\n"
                    + "/ZcwKEjb54s9T90aeKEZ9yZQn5lCjygv2Tfo63/wbwIDAQABo1MwUTAdBgNVHQ4E\n"
                    + "FgQUCZ/9kKF+Ly9vSewqKKBA6lu1Z54wHwYDVR0jBBgwFoAUCZ/9kKF+Ly9vSewq\n"
                    + "KKBA6lu1Z54wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAMmvu\n"
                    + "MBWDqan2L5Em/Pv2bvDK/v5Q3AFRQeGYmuQH07FlTtHNl/LRZAIFjdVgoD+m0tDC\n"
                    + "6N3aW21Ojq42BZP1wJJ4gLxJ/5Ug0Wm7XPgA5lL5+UCCFa1Rp+cT9YLwE9rFt+gt\n"
                    + "e2UmPJVx1PRMLvjU8DYjsPY9HhywkQOt5Nkw6kyZDQCfVqkVfFlc0VIEybsvy0o9\n"
                    + "ilmWWj9bRygES8eW7tF4Ta9uWMeZcXgS15VvBNt4SqIz7++ZscOeAPSWjZNHyZLf\n"
                    + "lTJugX4sIUOfK7VBYr+NlL3xk09GWiIjgVH1bqdE9XlR1+KuP3OytLv8xdzRIJKw\n"
                    + "x/H76dZstSOoKLrLmw==\n"
                    + "-----END CERTIFICATE-----";

    @TempDir
    Path tempDir;

    private CertificateStorageService service;

    @BeforeEach
    void setUp() {
        service = new CertificateStorageService(tempDir.toString());
    }

    @Test
    void rejectsPathTraversalCertId() {
        assertThrows(ParameterValidationException.class, () -> service.store("../evil", VALID_PEM));
    }

    @Test
    void storesAndListsCertificate() throws Exception {
        service.store("root-ca", VALID_PEM);
        assertTrue(Files.exists(tempDir.resolve("root-ca.crt")));
        assertEquals(1, service.listCertificates().size());
        assertEquals("root-ca", service.listCertificates().get(0).getCertId());
    }

    @Test
    void deleteRemovesCertificate() throws Exception {
        service.store("root-ca", VALID_PEM);
        service.deleteCertificate("root-ca");
        assertThrows(CertificateNotFoundException.class, () -> service.getCertificatePath("root-ca"));
    }
}
