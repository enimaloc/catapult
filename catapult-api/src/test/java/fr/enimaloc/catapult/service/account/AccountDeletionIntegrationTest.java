package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandSetting;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandSettingRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Against real foreign keys, which the mocks of {@link AccountServiceTest} can't see. */
@SpringBootTest
@ActiveProfiles("h2-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_account_deletion_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class AccountDeletionIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired ExpiredAccountPurge purge;
    @Autowired UserAccountRepository accounts;
    @Autowired ChatCommandSettingRepository settings;
    @MockitoBean TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    private UserAccount account() {
        String id = UUID.randomUUID().toString();
        UserAccount account = new UserAccount();
        account.setTwitchId(id);
        account.setTwitchUsername("u" + id.substring(0, 8));
        account.setStatus(UserAccount.Status.ACTIVE);
        return accounts.save(account);
    }

    private void withChatCommandSetting(UserAccount account) {
        ChatCommandSetting setting = new ChatCommandSetting();
        setting.setUser(account);
        setting.setKey("key");
        setting.setValue("value");
        settings.save(setting);
    }

    @Test
    void anAccountWithChatCommandSettings_isDeleted() {
        UserAccount account = account();
        withChatCommandSetting(account);

        accountService.deleteAccountImmediately(account);

        assertThat(accounts.findById(account.getId())).isEmpty();
        assertThat(settings.findByUser(account)).isEmpty();
    }

    @Test
    void thePurge_deletesAnExpiredAccount_andKeepsAPendingOneWithinTheDelay() {
        UserAccount expired = account();
        withChatCommandSetting(expired);
        expired.setStatus(UserAccount.Status.PENDING_DELETION);
        expired.setDeletionRequestedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        accounts.save(expired);
        UserAccount recent = account();
        recent.setStatus(UserAccount.Status.PENDING_DELETION);
        recent.setDeletionRequestedAt(Instant.now());
        accounts.save(recent);

        purge.purgeExpiredAccounts();

        assertThat(accounts.findById(expired.getId())).isEmpty();
        assertThat(accounts.findById(recent.getId())).isPresent();
    }
}
