package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.domain.binding.GameBinding.Status;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin game mappings' aggregate queries over game_binding: missing mappings, detected games, names, counts. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:game_mapping_queries;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GameBindingRepositoryMappingQueriesTest {

    @Autowired GameBindingRepository repository;
    @Autowired TestEntityManager em;

    private int users;

    private UserAccount streamer() {
        UserAccount account = new UserAccount();
        account.setTwitchUsername("s" + users);
        account.setTwitchId(String.valueOf(users++));
        return em.persist(account);
    }

    private void binding(UserAccount owner, SourceType type, String id, String name, Status status) {
        GameBinding b = new GameBinding();
        b.setUser(owner);
        b.setSourceType(type);
        b.setSourceId(id);
        b.setSourceName(name);
        b.setStatus(status);
        em.persist(b);
    }

    private void mapping(SourceType type, String id) {
        GameMapping m = new GameMapping();
        m.setSourceType(type);
        m.setSourceId(id);
        m.setTwitchGameId("1");
        m.setTwitchGameName("Cat");
        em.persist(m);
    }

    private static List<String> keys(Iterable<Object[]> rows) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        rows.forEach(r -> out.add(r[0] + ":" + r[1] + "=" + ((Number) r[2]).longValue()));
        return out;
    }

    @Test
    void missing_isIncompleteGamesWithoutMapping_byStreamerCountThenName() {
        UserAccount a = streamer(), b = streamer(), c = streamer();
        binding(a, SourceType.STEAM, "1", "Zeta", Status.INCOMPLETE);
        binding(b, SourceType.STEAM, "1", "Zeta", Status.AUTO);
        binding(a, SourceType.STEAM, "2", "Beta", Status.INCOMPLETE);
        binding(a, SourceType.XBOX, "3", "Alpha", Status.INCOMPLETE);
        binding(a, SourceType.STEAM, "4", "Mapped", Status.INCOMPLETE);
        mapping(SourceType.STEAM, "4");
        binding(c, SourceType.STEAM, "5", "Complete", Status.AUTO);
        binding(c, SourceType.MANUAL, "6", "Chat", Status.INCOMPLETE);
        em.flush();

        Page<Object[]> first = repository.findGamesMissingAMapping(PageRequest.of(0, 2));
        Page<Object[]> second = repository.findGamesMissingAMapping(PageRequest.of(1, 2));

        assertThat(keys(first)).containsExactly("STEAM:1=2", "XBOX:3=1");
        assertThat(keys(second)).containsExactly("STEAM:2=1");
        assertThat(first.getTotalElements()).isEqualTo(3);
    }

    @Test
    void detected_matchesIdPrefixOrName_onOnePlatform() {
        UserAccount a = streamer(), b = streamer();
        binding(a, SourceType.STEAM, "730", "Counter-Strike 2", Status.AUTO);
        binding(b, SourceType.STEAM, "730", "CS2", Status.MANUAL);
        binding(a, SourceType.STEAM, "1730", "Other", Status.AUTO);
        binding(a, SourceType.STEAM, "99", "Strike Force", Status.AUTO);
        binding(a, SourceType.XBOX, "730", "Counter-Strike 2", Status.AUTO);
        em.flush();

        List<Object[]> byId = repository.findDetectedGames(SourceType.STEAM, "73%", "%73%", PageRequest.of(0, 20));
        List<Object[]> byName = repository.findDetectedGames(SourceType.STEAM, "strike%", "%strike%", PageRequest.of(0, 20));
        List<Object[]> capped = repository.findDetectedGames(SourceType.STEAM, "%", "%", PageRequest.of(0, 2));

        assertThat(keys(byId)).containsExactly("STEAM:730=2");
        assertThat(keys(byName)).containsExactly("STEAM:730=2", "STEAM:99=1");
        assertThat(capped).hasSize(2);
    }

    @Test
    void sourceNamesAndStatusCounts_areGroupedByGame() {
        UserAccount a = streamer(), b = streamer(), c = streamer();
        binding(a, SourceType.STEAM, "730", "CS2", Status.MAPPED);
        binding(b, SourceType.STEAM, "730", "Counter-Strike 2", Status.MAPPED);
        binding(c, SourceType.STEAM, "730", "Counter-Strike 2", Status.MANUAL);
        em.flush();

        List<Object[]> names = repository.countSourceNames(List.of("730"));
        List<Object[]> mapped = repository.countByGameAndStatus(List.of("730"), Status.MAPPED);

        assertThat(names).extracting(r -> r[2] + "=" + ((Number) r[3]).longValue())
                .containsExactlyInAnyOrder("CS2=1", "Counter-Strike 2=2");
        assertThat(keys(mapped)).containsExactly("STEAM:730=2");
    }

    @Test
    void missing_countsOnlyTheStreamersAMappingWouldChange() {
        UserAccount a = streamer(), b = streamer();
        binding(a, SourceType.STEAM, "1", "Game", Status.INCOMPLETE);
        binding(b, SourceType.STEAM, "1", "Game", Status.MANUAL);
        // Only MANUAL bindings left beside nothing incomplete: not listed.
        binding(b, SourceType.STEAM, "2", "Other", Status.MANUAL);
        em.flush();

        assertThat(keys(repository.findGamesMissingAMapping(PageRequest.of(0, 10)))).containsExactly("STEAM:1=1");
        assertThat(repository.findGamesMissingAMapping(PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    void lockingFinder_returnsTheGamesBindingsInTheStatuses() {
        UserAccount a = streamer(), b = streamer(), c = streamer();
        binding(a, SourceType.STEAM, "730", "CS2", Status.AUTO);
        binding(b, SourceType.STEAM, "730", "CS2", Status.MANUAL);
        binding(c, SourceType.STEAM, "730", "CS2", Status.MAPPED);
        binding(c, SourceType.XBOX, "730", "CS2", Status.AUTO);
        em.flush();
        em.clear();

        List<GameBinding> locked = repository.lockAllBySourceTypeAndSourceIdAndStatusIn(
                SourceType.STEAM, "730", List.of(Status.AUTO, Status.INCOMPLETE, Status.MAPPED));

        assertThat(locked).extracting(GameBinding::getStatus).containsExactlyInAnyOrder(Status.AUTO, Status.MAPPED);
        assertThat(locked).allSatisfy(binding -> assertThat(binding.getCcls()).isEmpty());
    }
}
