package com.fx.ops;

// A tiny probe to confirm from the running app that the DB came up seeded:
// curl localhost:8080/api/health/db.

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DbHealthController {

    private static final List<String> TABLES = List.of("currency", "account", "fx_rate", "transfer");

    private final JdbcTemplate jdbc;

    public DbHealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health/db")
    public Map<String, Object> dbHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            out.put("status", "UP");
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String table : TABLES) {
                try {
                    counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
                } catch (Exception e) {
                    counts.put(table, "missing");
                }
            }
            out.put("tables", counts);
        } catch (Exception e) {
            out.put("status", "DOWN");
            out.put("hint", "Is MySQL running, and does fxdb exist with user appuser? " + e.getMessage());
        }
        return out;
    }
}
