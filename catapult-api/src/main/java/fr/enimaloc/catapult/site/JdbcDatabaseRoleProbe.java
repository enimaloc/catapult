package fr.enimaloc.catapult.site;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcDatabaseRoleProbe implements DatabaseRoleProbe {

    private final JdbcTemplate jdbc;

    /** Whether the database is PostgreSQL, looked up once; {@code null} until a lookup succeeds. */
    private volatile Boolean postgres;

    @Override
    public DatabaseRole role() {
        try {
            if (!isPostgres()) {
                // H2 (tests, local runs) has no replica
                return DatabaseRole.WRITABLE;
            }
            return Boolean.TRUE.equals(jdbc.queryForObject("select pg_is_in_recovery()", Boolean.class))
                ? DatabaseRole.IN_RECOVERY
                : DatabaseRole.WRITABLE;
        } catch (RuntimeException e) {
            // A doubt, not a confirmed replica: SiteRole decides how long to wait on it
            log.debug("Could not read the database role", e);
            return DatabaseRole.UNKNOWN;
        }
    }

    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((Connection c) ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName())));
            postgres = known;
        }
        return known;
    }
}
