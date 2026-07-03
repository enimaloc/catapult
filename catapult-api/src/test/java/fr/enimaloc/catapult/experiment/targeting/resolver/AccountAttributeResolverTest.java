package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAttributeResolverTest {

    private final AccountAttributeResolver resolver = new AccountAttributeResolver();

    @Test
    void resolvesAccountStatusAsText() {
        UserAccount user = new UserAccount();
        user.setStatus(UserAccount.Status.ACTIVE);
        assertThat(resolver.supports("account_status")).isTrue();
        assertThat(resolver.resolve(user, "account_status"))
            .isEqualTo(AttributeValue.text("ACTIVE"));
    }

    @Test
    void resolvesHasSteamAsNumber() {
        UserAccount user = new UserAccount();
        user.setSteamId("76500");
        assertThat(resolver.resolve(user, "has_steam"))
            .isEqualTo(AttributeValue.number(1));
    }

    @Test
    void unsupportedKeyReturnsMissing() {
        assertThat(resolver.supports("group")).isFalse();
        assertThat(resolver.resolve(new UserAccount(), "group"))
            .isEqualTo(AttributeValue.missing());
    }
}
