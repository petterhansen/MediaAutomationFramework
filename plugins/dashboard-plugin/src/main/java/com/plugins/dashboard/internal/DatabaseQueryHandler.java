package com.plugins.dashboard.internal;

import com.framework.services.database.DatabaseService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * HTTP handler for database query operations
 */
public class DatabaseQueryHandler implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseQueryHandler.class);
    private final DatabaseService databaseService;
    private final Gson gson = new Gson();

    public DatabaseQueryHandler(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if (path.equals("/api/database/query") && method.equals("POST")) {
                handleQuery(exchange);
            } else if (path.equals("/api/database/tables") && method.equals("GET")) {
                handleGetTables(exchange);
            } else if (path.equals("/api/database/schema") && method.equals("GET")) {
                handleGetSchema(exchange);
            } else {
                sendResponse(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            logger.error("Error handling database request", e);
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = gson.fromJson(body, Map.class);
        String sql = (String) request.get("query");

        if (sql == null || sql.trim().isEmpty()) {
            sendResponse(exchange, 400, "{\"error\":\"Query is required\"}");
            return;
        }

        logger.info("Executing SQL query: {}", sql);

        try {
            JsonObject result = databaseService.getJdbi().withHandle(handle -> {
                JsonObject response = new JsonObject();

                // Check if it's a SELECT query
                String trimmedSql = sql.trim().toUpperCase();
                if (trimmedSql.startsWith("SELECT") || trimmedSql.startsWith("SHOW")) {
                    // Query that returns results
                    List<Map<String, Object>> rows = handle.createQuery(sql)
                            .mapToMap()
                            .list();

                    JsonArray columns = new JsonArray();
                    JsonArray data = new JsonArray();

                    if (!rows.isEmpty()) {
                        // Extract column names from first row
                        rows.get(0).keySet().forEach(columns::add);

                        // Convert rows to JSON
                        for (Map<String, Object> row : rows) {
                            JsonArray rowArray = new JsonArray();
                            for (String col : rows.get(0).keySet()) {
                                Object value = row.get(col);
                                if (value == null) {
                                    rowArray.add((String) null);
                                } else {
                                    rowArray.add(value.toString());
                                }
                            }
                            data.add(rowArray);
                        }
                    }

                    response.add("columns", columns);
                    response.add("data", data);
                    response.addProperty("rowCount", rows.size());
                    response.addProperty("type", "select");
                } else {
                    // UPDATE, INSERT, DELETE, etc.
                    int affectedRows = handle.createUpdate(sql).execute();
                    response.addProperty("affectedRows", affectedRows);
                    response.addProperty("type", "update");
                }

                response.addProperty("success", true);
                return response;
            });

            sendResponse(exchange, 200, gson.toJson(result));
        } catch (Exception e) {
            logger.error("Query execution failed", e);
            JsonObject error = new JsonObject();
            error.addProperty("success", false);
            error.addProperty("error", e.getMessage());
            sendResponse(exchange, 400, gson.toJson(error));
        }
    }

    private void handleGetTables(HttpExchange exchange) throws IOException {
        try {
            List<String> tables = databaseService.getJdbi().withHandle(handle -> {
                return handle.createQuery(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                                "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME")
                        .mapTo(String.class)
                        .list();
            });

            JsonObject response = new JsonObject();
            JsonArray tablesArray = new JsonArray();
            tables.forEach(tablesArray::add);
            response.add("tables", tablesArray);

            sendResponse(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            logger.error("Failed to get tables", e);
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleGetSchema(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String tableName = null;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && pair[0].equals("table")) {
                    tableName = pair[1];
                }
            }
        }

        if (tableName == null) {
            sendResponse(exchange, 400, "{\"error\":\"Table name is required\"}");
            return;
        }

        try {
            final String table = tableName;
            List<Map<String, Object>> columns = databaseService.getJdbi().withHandle(handle -> {
                return handle.createQuery(
                        "SELECT COLUMN_NAME, TYPE_NAME, IS_NULLABLE, COLUMN_DEFAULT " +
                                "FROM INFORMATION_SCHEMA.COLUMNS " +
                                "WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION")
                        .bind(0, table.toUpperCase())
                        .mapToMap()
                        .list();
            });

            JsonObject response = new JsonObject();
            response.addProperty("table", tableName);
            response.add("columns", gson.toJsonTree(columns));

            sendResponse(exchange, 200, gson.toJson(response));
        } catch (Exception e) {
            logger.error("Failed to get schema", e);
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
