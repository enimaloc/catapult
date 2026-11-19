package fr.enimaloc.catapult.repository.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:emergencydb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmergencyRepositoriesIntegrationTest {

    @Autowired ProviderEmergencyStopRepository stops;
    @Autowired EmergencyReportRepository reports;
    @Autowired TestEntityManager em;

    private final Instant now = Instant.now();

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    private ProviderEmergencyStop stop(Instant expiresAt, Instant liftedAt) {
        ProviderEmergencyStop s = new ProviderEmergencyStop();
        s.setProvider("STEAM");
        s.setScope(ProviderEmergencyStop.Scope.GLOBAL);
        s.setOrigin(ProviderEmergencyStop.Origin.OWNER);
        s.setExpiresAt(expiresAt);
        s.setLiftedAt(liftedAt);
        return em.persist(s);
    }

    private EmergencyReport report(UserAccount channel, Instant createdAt, Instant withdrawnAt) {
        EmergencyReport r = new EmergencyReport();
        r.setChannel(channel);
        r.setProvider(GetterConfig.Provider.STEAM);
        r.setReportedByTwitchId("viewer");
        r.setReportedByLogin("viewer");
        r.setCreatedAt(createdAt);
        r.setWithdrawnAt(withdrawnAt);
        r.setContext("{\"game\":\"x\"}");
        return em.persist(r);
    }

    @Test
    void findActive_excludesLiftedAndExpired() {
        ProviderEmergencyStop open = stop(null, null);
        ProviderEmergencyStop tomorrow = stop(now.plus(Duration.ofDays(1)), null);
        stop(now.minus(Duration.ofDays(1)), null);
        stop(null, now.minusSeconds(5));
        em.flush();

        List<ProviderEmergencyStop> active = stops.findActive(now);

        assertThat(active).containsExactlyInAnyOrder(open, tomorrow);
        assertThat(active).allMatch(s -> s.isActive(now));
    }

    @Test
    void findCounting_ignoresWithdrawnAndOld() {
        UserAccount channel = user("chan");
        EmergencyReport counted = report(channel, now.minusSeconds(60), null);
        report(channel, now.minusSeconds(60), now.minusSeconds(10));
        report(channel, now.minus(Duration.ofMinutes(20)), null);
        em.flush();

        List<EmergencyReport> result = reports.findCounting(GetterConfig.Provider.STEAM, now.minus(Duration.ofMinutes(15)));

        assertThat(result).containsExactly(counted);
        assertThat(result.get(0).getContext()).contains("\"game\"");
    }
}
