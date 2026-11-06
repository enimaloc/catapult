package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingScheduleRefusal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomBindingScheduleRepositoryTest {

    @Autowired CustomBindingScheduleRepository schedules;
    @Autowired CustomBindingScheduleRefusalRepository refusals;
    @Autowired CustomBindingActivationRepository activations;
    @Autowired CustomBindingRepository bindings;
    @Autowired TestEntityManager em;

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    private CustomBinding binding(UserAccount user, String name) {
        CustomBinding b = new CustomBinding();
        b.setUser(user);
        b.setName(name);
        b.setTwitchGameId("509660");
        b.setTwitchGameName("Art");
        return bindings.save(b);
    }

    private CustomBindingSchedule schedule(CustomBinding binding, LocalDateTime start,
                                           CustomBindingSchedule.Repeat repeat, boolean paused) {
        CustomBindingSchedule s = new CustomBindingSchedule();
        s.setCustomBinding(binding);
        s.setStartsAt(start);
        s.setEndsAt(start.plusHours(2));
        s.setRepeat(repeat);
        s.setPaused(paused);
        return schedules.save(s);
    }

    @Test
    void schedulesListInStartOrderWithTheirDays() {
        CustomBinding art = binding(user("one"), "Art");
        CustomBindingSchedule weekly = schedule(art, LocalDateTime.of(2026, 11, 10, 20, 0),
                CustomBindingSchedule.Repeat.WEEKLY, false);
        weekly.getDays().addAll(Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY));
        schedule(art, LocalDateTime.of(2026, 11, 8, 18, 0), CustomBindingSchedule.Repeat.NONE, false);
        em.flush();
        em.clear();

        var found = schedules.findByCustomBindingOrderByStartsAtAsc(art);

        assertThat(found).extracting(CustomBindingSchedule::getRepeat)
                .containsExactly(CustomBindingSchedule.Repeat.NONE, CustomBindingSchedule.Repeat.WEEKLY);
        assertThat(found.get(1).getDays()).containsExactlyInAnyOrder(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY);
        assertThat(schedules.findByCustomBindingUserId(art.getUser().getId())).hasSize(2);
    }

    @Test
    void unpausedSchedulesNameTheirUsersOnce() {
        UserAccount a = user("a");
        UserAccount b = user("b");
        CustomBinding artA = binding(a, "Art");
        schedule(artA, LocalDateTime.of(2026, 11, 10, 20, 0), CustomBindingSchedule.Repeat.DAILY, false);
        schedule(artA, LocalDateTime.of(2026, 11, 11, 20, 0), CustomBindingSchedule.Repeat.NONE, false);
        schedule(binding(b, "Art"), LocalDateTime.of(2026, 11, 10, 20, 0), CustomBindingSchedule.Repeat.NONE, true);
        em.flush();
        em.clear();

        assertThat(schedules.findUserIdsWithUnpausedSchedules()).containsExactly(a.getId());
    }

    @Test
    void activationOriginDefaultsToManual() {
        UserAccount a = user("a");
        activations.save(new CustomBindingActivation(a.getId(), binding(a, "Art"), Instant.now()));
        em.flush();
        em.clear();

        assertThat(activations.findById(a.getId())).get()
                .satisfies(x -> assertThat(x.getOrigin()).isEqualTo(CustomBindingActivation.Origin.MANUAL));
        assertThat(activations.findUserIdsByOrigin(CustomBindingActivation.Origin.SCHEDULED)).isEmpty();
        assertThat(activations.findUserIdsByOrigin(CustomBindingActivation.Origin.MANUAL)).containsExactly(a.getId());
    }

    @Test
    void refusalsEndedBeforeArePurged() {
        CustomBindingSchedule s = schedule(binding(user("a"), "Art"), LocalDateTime.of(2026, 11, 10, 20, 0),
                CustomBindingSchedule.Repeat.DAILY, false);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        refusals.save(new CustomBindingScheduleRefusal(s.getId(), now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)));
        refusals.save(new CustomBindingScheduleRefusal(s.getId(), now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS)));
        em.flush();

        refusals.deleteByOccurrenceEndBefore(now);
        em.clear();

        assertThat(refusals.findAll()).hasSize(1);
        assertThat(refusals.existsByScheduleIdAndOccurrenceStart(s.getId(), now.minus(1, ChronoUnit.HOURS))).isTrue();
        assertThat(refusals.existsByScheduleIdAndOccurrenceStart(s.getId(), now.minus(3, ChronoUnit.HOURS))).isFalse();
    }
}
