package com.yugabyte.simulation.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SqlScriptExecutorTest {

    @Test
    void splitsDollarQuotedFunctionAsSingleStatement() {
        String sql = "CREATE TABLE t (id int);\n"
                + "CREATE OR REPLACE FUNCTION foo()\n"
                + "RETURNS void\n"
                + "LANGUAGE plpgsql\n"
                + "AS $$\n"
                + "BEGIN\n"
                + "    INSERT INTO t VALUES (1);\n"
                + "    UPDATE t SET id = 2;\n"
                + "END;\n"
                + "$$;\n"
                + "CREATE INDEX idx ON t (id);\n";
        List<String> statements = SqlScriptExecutor.splitStatements(sql);
        assertTrue(statements.size() >= 3);
        assertTrue(contains(statements, "CREATE TABLE t"));
        boolean functionIsOneStatement = false;
        for (String statement : statements) {
            if (statement.contains("CREATE OR REPLACE FUNCTION foo")
                    && statement.contains("INSERT INTO t VALUES (1)")
                    && statement.contains("UPDATE t SET id = 2")
                    && !statement.trim().endsWith("CREATE INDEX")) {
                functionIsOneStatement = true;
                break;
            }
        }
        assertTrue(functionIsOneStatement);
        assertTrue(contains(statements, "CREATE INDEX idx"));
    }

    @Test
    void skipsBeginCommitAndCommentOnlyStatements() {
        List<String> executable = executableStatements("BEGIN;\n-- just a comment\nCOMMIT;\n");
        assertTrue(executable.isEmpty());
        assertFalse(SqlScriptExecutor.isExecutable("BEGIN"));
        assertFalse(SqlScriptExecutor.isExecutable("COMMIT"));
    }

    @Test
    void splitsRtpSchemaWithCompletePaymentEventsAndFunctions() {
        String script = loadClasspath("scripts/rtp-schema.sql");
        List<String> executable = executableStatements(script);
        assertTrue(executable.size() > 40, "expected dozens of executable statements, got " + executable.size());

        assertTrue(matches(executable, "CREATE TABLE payment_events", "PARTITION BY LIST (geo_partition)")
                && noneContains(executable, "CREATE TABLE payment_events", "CREATE TABLE outbox_events"));
        assertTrue(matches(executable, "CREATE TABLE outbox_events")
                && noneContains(executable, "CREATE TABLE outbox_events", "CREATE TABLE payment_events"));
        assertTrue(matches(executable, "CREATE OR REPLACE FUNCTION transition_payment",
                "INSERT INTO payment_events", "INSERT INTO outbox_events"));
        assertTrue(matches(executable, "CREATE OR REPLACE FUNCTION settle_outbound_payment"));
        assertTrue(matches(executable, "CREATE OR REPLACE FUNCTION claim_outbox_events"));
    }

    private static List<String> executableStatements(String script) {
        List<String> executable = new ArrayList<String>();
        for (String statement : SqlScriptExecutor.splitStatements(script)) {
            if (SqlScriptExecutor.isExecutable(statement)) {
                executable.add(statement);
            }
        }
        return executable;
    }

    private static boolean contains(List<String> statements, String snippet) {
        for (String statement : statements) {
            if (statement.contains(snippet)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(List<String> statements, String... snippets) {
        for (String statement : statements) {
            boolean all = true;
            for (String snippet : snippets) {
                if (!statement.contains(snippet)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean noneContains(List<String> statements, String required, String forbidden) {
        for (String statement : statements) {
            if (statement.contains(required) && statement.contains(forbidden)) {
                return false;
            }
        }
        return true;
    }

    private static String loadClasspath(String resourcePath) {
        InputStream in = SqlScriptExecutorTest.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing " + resourcePath);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
