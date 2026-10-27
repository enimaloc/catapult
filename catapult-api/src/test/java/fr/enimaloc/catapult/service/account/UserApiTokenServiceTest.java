package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserApiToken;
import fr.enimaloc.catapult.repository.account.UserApiTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:apitoken;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserApiTokenService.class)
class UserApiTokenServiceTest {

    @Autowired UserApiTokenService service;
    @Autowired UserApiTokenRepository repository;
    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;

    private UserAccount user(String login) {
        UserAccount user = new UserAccount();
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return em.persist(user);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void generatedToken_isCatPrefixed_32RandomBytes_base64urlWithoutPadding() {
        ApiTokenCreatedDto created = service.regenerate(user("one"));

        assertThat(created.token()).startsWith("cat_").doesNotContain("=", "+", "/");
        byte[] raw = Base64.getUrlDecoder().decode(created.token().substring(4));
        assertThat(raw).hasSize(32);
        assertThat(created.createdAt()).isNotNull();
        assertThat(service.regenerate(user("two")).token()).isNotEqualTo(created.token());
    }

    @Test
    void tokenIsStoredHashedOnly() throws Exception {
        UserAccount user = user("one");
        ApiTokenCreatedDto created = service.regenerate(user);
        em.flush();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM user_api_token");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().values()).noneMatch(v -> v != null && v.toString().contains(created.token().substring(4)));
        UserApiToken stored = repository.findById(user.getId()).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(sha256(created.token()));
    }

    @Test
    void theTokenAuthenticatesItsOwner_andNothingElseDoes() {
        UserAccount user = user("one");
        user("two");
        ApiTokenCreatedDto created = service.regenerate(user);

        assertThat(service.authenticate(created.token())).get().extracting(UserAccount::getId).isEqualTo(user.getId());
        assertThat(service.authenticate(created.token() + "x")).isEmpty();
        assertThat(service.authenticate("cat_" + "A".repeat(43))).isEmpty();
        assertThat(service.authenticate(user.getId().toString())).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void regeneratedToken_oldOneStopsWorking() {
        UserAccount user = user("one");
        ApiTokenCreatedDto first = service.regenerate(user);
        ApiTokenCreatedDto second = service.regenerate(user);
        em.flush();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(service.authenticate(first.token())).isEmpty();
        assertThat(service.authenticate(second.token())).isPresent();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void status_tellsWhetherThereIsOne_andRevokeEndsIt() {
        UserAccount user = user("one");
        assertThat(service.status(user).exists()).isFalse();
        assertThat(service.status(user).createdAt()).isNull();

        ApiTokenCreatedDto created = service.regenerate(user);
        assertThat(service.status(user).exists()).isTrue();
        assertThat(service.status(user).createdAt()).isEqualTo(created.createdAt());

        service.revoke(user);
        em.flush();
        assertThat(service.status(user).exists()).isFalse();
        assertThat(service.authenticate(created.token())).isEmpty();
        service.revoke(user); // nothing left: no error
    }
}
