package fr.enimaloc.catapult.domain.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSettingsTwFieldsTest {

    @Test
    void defaults_twFeatureEnabledTrue_blockedTwsEmpty() {
        UserSettings s = new UserSettings();
        assertThat(s.isTwFeatureEnabled()).isTrue();
        assertThat(s.getBlockedTws()).isEmpty();
    }
}
