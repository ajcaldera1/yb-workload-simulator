package com.yugabyte.simulation.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits and executes SQL scripts that may contain PostgreSQL/YSQL dollar-quoted
 * function bodies, comments, and quoted strings. Statements are run one at a time
 * on a single connection so session settings such as {@code search_path} stick.
 */
public final class SqlScriptExecutor {

    private SqlScriptExecutor() {
    }

    public static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int end = script.indexOf('\n', i);
                if (end < 0) {
                    current.append(script.substring(i));
                    break;
                }
                current.append(script, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated block comment");
                }
                current.append(script, i, end + 2);
                i = end + 2;
                continue;
            }
            if (c == '$') {
                int tagEnd = dollarTagEnd(script, i);
                if (tagEnd > i) {
                    String tag = script.substring(i, tagEnd);
                    int close = script.indexOf(tag, tagEnd);
                    if (close < 0) {
                        throw new IllegalArgumentException("Unterminated dollar-quote " + tag);
                    }
                    current.append(script, i, close + tag.length());
                    i = close + tag.length();
                    continue;
                }
            }
            if (c == '\'') {
                current.append(c);
                i++;
                while (i < n) {
                    char q = script.charAt(i);
                    current.append(q);
                    i++;
                    if (q == '\'') {
                        if (i < n && script.charAt(i) == '\'') {
                            current.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == '"') {
                current.append(c);
                i++;
                while (i < n) {
                    char q = script.charAt(i);
                    current.append(q);
                    i++;
                    if (q == '"') {
                        if (i < n && script.charAt(i) == '"') {
                            current.append('"');
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == ';') {
                addIfPresent(statements, current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addIfPresent(statements, current.toString());
        return statements;
    }

    public static int execute(Connection conn, String script) throws SQLException {
        int executed = 0;
        try (Statement statement = conn.createStatement()) {
            for (String sql : splitStatements(script)) {
                if (!isExecutable(sql)) {
                    continue;
                }
                try {
                    statement.execute(sql);
                    executed++;
                } catch (SQLException e) {
                    if (isIgnorableExtensionError(sql, e)) {
                        continue;
                    }
                    throw new SQLException("Failed executing SQL: " + preview(sql) + " — " + e.getMessage(), e);
                }
            }
        }
        return executed;
    }

    static boolean isExecutable(String sql) {
        String stripped = stripComments(sql).trim();
        if (stripped.isEmpty()) {
            return false;
        }
        String upper = stripped.toUpperCase(Locale.ROOT);
        return !upper.equals("BEGIN")
                && !upper.equals("COMMIT")
                && !upper.equals("ROLLBACK")
                && !upper.equals("END");
    }

    private static void addIfPresent(List<String> statements, String raw) {
        String stmt = raw.trim();
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
    }

    private static int dollarTagEnd(String script, int start) {
        if (script.charAt(start) != '$') {
            return start;
        }
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '$') {
                return i + 1;
            }
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return start;
            }
            i++;
        }
        return start;
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > i) {
                    String tag = sql.substring(i, tagEnd);
                    int close = sql.indexOf(tag, tagEnd);
                    if (close < 0) {
                        out.append(sql.substring(i));
                        break;
                    }
                    i = close + tag.length();
                    out.append('x');
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    char q = sql.charAt(i++);
                    if (q == quote) {
                        if (i < n && sql.charAt(i) == quote) {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                out.append('x');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isIgnorableExtensionError(String sql, SQLException e) {
        String stripped = stripComments(sql).trim().toUpperCase(Locale.ROOT);
        return stripped.startsWith("CREATE EXTENSION") && e.getMessage() != null;
    }

    private static String preview(String sql) {
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "...";
    }
}
