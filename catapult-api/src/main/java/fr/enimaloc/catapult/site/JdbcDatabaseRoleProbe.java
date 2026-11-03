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

    @Override
    public boolean writable() {
        try {
            boolean postgres = Boolean.TRUE.equals(jdbc.execute((Connection c) ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName())));
            if (!postgres) {
                return true;
            }
            return Boolean.FALSE.equals(jdbc.queryForObject("select pg_is_in_recovery()", Boolean.class));
        } catch (RuntimeException e) {
            log.warn("Could not read the database role, treating it as not writable", e);
            return false;
        }
    }
}
