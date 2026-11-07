package fr.enimaloc.catapult.site;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcDatabaseRoleProbeTest {

    private static final String IN_RECOVERY_QUERY = "select pg_is_in_recovery()";

    @Mock JdbcTemplate jdbc;
    @InjectMocks JdbcDatabaseRoleProbe probe;

    @Test
    void notPostgres_isWritable() {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(false);

        assertEquals(DatabaseRole.WRITABLE, probe.role());
        verify(jdbc, never()).queryForObject(IN_RECOVERY_QUERY, Boolean.class);
    }

    @Test
    void postgresPrimary_isWritable_andReplica_isInRecovery() {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(jdbc.queryForObject(IN_RECOVERY_QUERY, Boolean.class)).thenReturn(false, true);

        assertEquals(DatabaseRole.WRITABLE, probe.role());
        assertEquals(DatabaseRole.IN_RECOVERY, probe.role());
    }

    @Test
    void failingQuery_isUnknown() {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(jdbc.queryForObject(IN_RECOVERY_QUERY, Boolean.class))
            .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertEquals(DatabaseRole.UNKNOWN, probe.role());
    }

    @Test
    void failingProductLookup_isUnknown_andRetriedNextTime() {
        when(jdbc.execute(any(ConnectionCallback.class)))
            .thenThrow(new DataAccessResourceFailureException("connection refused"))
            .thenReturn(false);

        assertEquals(DatabaseRole.UNKNOWN, probe.role());
        assertEquals(DatabaseRole.WRITABLE, probe.role());
    }

    @Test
    void productName_isLookedUpOnce() {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(jdbc.queryForObject(IN_RECOVERY_QUERY, Boolean.class)).thenReturn(false);

        probe.role();
        probe.role();

        verify(jdbc, times(1)).execute(any(ConnectionCallback.class));
    }
}
