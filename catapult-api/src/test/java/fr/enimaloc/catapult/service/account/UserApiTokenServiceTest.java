package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.domain.account.ApiTokenScope;
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
        ApiTokenCreatedDto created = service.regenerate(user("one"), ApiTokenScope.FULL);

        assertThat(created.token()).startsWith("cat_").doesNotContain("=", "+", "/");
        byte[] raw = Base64.getUrlDecoder().decode(created.token().substring(4));
        assertThat(raw).hasSize(32);
        assertThat(created.createdAt()).isNotNull();
        assertThat(service.regenerate(user("two"), ApiTokenScope.FULL).token()).isNotEqualTo(created.token());
    }

    @Test
    void tokenIsStoredHashedOnly() throws Exception {
        UserAccount user = user("one");
        ApiTokenCreatedDto created = service.regenerate(user, ApiTokenScope.FULL);
        em.flush();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM user_api_token");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().values()).noneMatch(v -> v != null && v.toString().contains(created.token().substring(4)));
        UserApiToken stored = repository.findByUserIdAndScope(user.getId(), ApiTokenScope.FULL).orElseThrow();
        assertThat(stored.getTokenHash()).isEqualTo(sha256(created.token()));
    }

    @Test
    void theTokenAuthenticatesItsOwner_andNothingElseDoes() {
        UserAccount user = user("one");
        user("two");
        ApiTokenCreatedDto created = service.regenerate(user, ApiTokenScope.FULL);

        assertThat(service.authenticate(created.token())).get().extracting(ApiTokenOwner::user).extracting(UserAccount::getId).isEqualTo(user.getId());
        assertThat(service.authenticate(created.token() + "x")).isEmpty();
        assertThat(service.authenticate("cat_" + "A".repeat(43))).isEmpty();
        assertThat(service.authenticate(user.getId().toString())).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(null)).isEmpty();
    }

    @Test
    void regeneratedToken_oldOneStopsWorking() {
        UserAccount user = user("one");
        ApiTokenCreatedDto first = service.regenerate(user, ApiTokenScope.FULL);
        ApiTokenCreatedDto second = service.regenerate(user, ApiTokenScope.FULL);
        em.flush();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(service.authenticate(first.token())).isEmpty();
        assertThat(service.authenticate(second.token())).isPresent();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void status_tellsWhetherThereIsOne_andRevokeEndsIt() {
        UserAccount user = user("one");
        assertThat(service.status(user, ApiTokenScope.FULL).exists()).isFalse();
        assertThat(service.status(user, ApiTokenScope.FULL).createdAt()).isNull();

        ApiTokenCreatedDto created = service.regenerate(user, ApiTokenScope.FULL);
        assertThat(service.status(user, ApiTokenScope.FULL).exists()).isTrue();
        assertThat(service.status(user, ApiTokenScope.FULL).createdAt()).isEqualTo(created.createdAt());

        service.revoke(user, ApiTokenScope.FULL);
        em.flush();
        assertThat(service.status(user, ApiTokenScope.FULL).exists()).isFalse();
        assertThat(service.authenticate(created.token())).isEmpty();
        service.revoke(user, ApiTokenScope.FULL); // nothing left: no error
    }

    @Test
    void readToken_startsWithCatr_andAuthenticatesAsRead() {
        UserAccount user = user("one");
        ApiTokenCreatedDto created = service.regenerate(user, ApiTokenScope.READ);

        assertThat(created.token()).startsWith("catr_").doesNotContain("=", "+", "/");
        assertThat(Base64.getUrlDecoder().decode(created.token().substring(5))).hasSize(32);
        assertThat(service.authenticate(created.token())).get()
                .satisfies(owner -> {
                    assertThat(owner.user().getId()).isEqualTo(user.getId());
                    assertThat(owner.scope()).isEqualTo(ApiTokenScope.READ);
                });
    }

    @Test
    void fullToken_authenticatesAsFull() {
        ApiTokenCreatedDto created = service.regenerate(user("one"), ApiTokenScope.FULL);

        assertThat(service.authenticate(created.token())).get()
                .extracting(ApiTokenOwner::scope).isEqualTo(ApiTokenScope.FULL);
    }

    @Test
    void regeneratingRead_leavesFullWorking() {
        UserAccount user = user("one");
        ApiTokenCreatedDto full = service.regenerate(user, ApiTokenScope.FULL);
        ApiTokenCreatedDto firstRead = service.regenerate(user, ApiTokenScope.READ);
        ApiTokenCreatedDto secondRead = service.regenerate(user, ApiTokenScope.READ);
        em.flush();

        assertThat(service.authenticate(full.token())).get()
                .extracting(ApiTokenOwner::scope).isEqualTo(ApiTokenScope.FULL);
        assertThat(service.authenticate(firstRead.token())).isEmpty();
        assertThat(service.authenticate(secondRead.token())).isPresent();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void revokeRead_leavesFull() {
        UserAccount user = user("one");
        ApiTokenCreatedDto full = service.regenerate(user, ApiTokenScope.FULL);
        ApiTokenCreatedDto read = service.regenerate(user, ApiTokenScope.READ);

        service.revoke(user, ApiTokenScope.READ);
        em.flush();

        assertThat(service.status(user, ApiTokenScope.READ).exists()).isFalse();
        assertThat(service.status(user, ApiTokenScope.FULL).exists()).isTrue();
        assertThat(service.authenticate(read.token())).isEmpty();
        assertThat(service.authenticate(full.token())).isPresent();
    }

    @Test
    void fullTokenWithReadPrefixSwapped_isRejected() {
        ApiTokenCreatedDto full = service.regenerate(user("one"), ApiTokenScope.FULL);
        ApiTokenCreatedDto read = service.regenerate(user("two"), ApiTokenScope.READ);

        assertThat(service.authenticate("catr_" + full.token().substring(4))).isEmpty();
        assertThat(service.authenticate("cat_" + read.token().substring(5))).isEmpty();
    }
}
