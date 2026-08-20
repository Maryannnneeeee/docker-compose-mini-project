package com.fx.api.repo;

import com.fx.api.model.Rate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Spring JDBC — JdbcTemplate over the fxdb schema. */
@Repository
public class RateRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Rate> MAPPER = (rs, n) -> {
        Timestamp capturedAt = rs.getTimestamp("captured_at");
        return new Rate(
                rs.getInt("id"), rs.getString("base_code"), rs.getString("quote_code"),
                rs.getDouble("rate"), rs.getDate("rate_date").toLocalDate(),
                capturedAt == null ? null : capturedAt.toInstant());
    };

    public RateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }   // constructor injection

    /** The single newest row per pair — by insertion order, not just by date, so a live
     * feed ticking several rows into the same day still returns one row per pair. */
    public List<Rate> findLatest() {
        return jdbc.query("""
            SELECT r.* FROM fx_rate r
            INNER JOIN (
                SELECT base_code, quote_code, MAX(id) AS max_id
                FROM fx_rate GROUP BY base_code, quote_code
            ) latest ON r.id = latest.max_id
            ORDER BY r.base_code, r.quote_code""", MAPPER);
    }

    public Optional<Rate> findLatestForPair(String base, String quote) {
        List<Rate> rows = jdbc.query("""
            SELECT * FROM fx_rate WHERE base_code=? AND quote_code=?
            ORDER BY rate_date DESC, id DESC LIMIT 1""", MAPPER, base, quote);
        return rows.stream().findFirst();
    }

    public int insert(String base, String quote, double rate) {
        return jdbc.update("INSERT INTO fx_rate (base_code, quote_code, rate, rate_date) VALUES (?,?,?,CURDATE())",
                base, quote, rate);
    }

    /** A live-feed tick: a new row stamped with the instant it was generated. */
    public int insertTick(String base, String quote, double rate, Instant capturedAt) {
        return jdbc.update("""
            INSERT INTO fx_rate (base_code, quote_code, rate, rate_date, captured_at)
            VALUES (?,?,?,CURDATE(),?)""",
                base, quote, rate, Timestamp.from(capturedAt));
    }
}
