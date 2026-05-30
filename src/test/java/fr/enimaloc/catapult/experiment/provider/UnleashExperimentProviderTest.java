package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.UserAccount;
import io.getunleash.FakeUnleash;
import io.getunleash.Variant;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnleashExperimentProviderTest {

    private ExperimentProviderProperties buildProps() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        ExperimentProviderProperties.Unleash ul = new ExperimentProviderProperties.Unleash();
        ul.setApiUrl("https://unleash.example.com");
        ul.setClientKey("secret");
        props.setUnleash(ul);
        return props;
    }

    @Test
    void typeIsUnleash() {
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), new FakeUnleash());
        assertThat(provider.type()).isEqualTo("unleash");
    }

    @Test
    void adminUrlFromConfig() {
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), new FakeUnleash());
        assertThat(provider.adminUrl()).contains("https://unleash.example.com");
    }

    @Test
    void getVariantReturnsEmptyWhenDisabled() {
        FakeUnleash fake = new FakeUnleash();
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), fake);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        assertThat(provider.getVariant(user, "disabled-toggle")).isEmpty();
    }

    @Test
    void getVariantReturnsMappedVariant() {
        FakeUnleash fake = new FakeUnleash();
        fake.enable("my-toggle");
        fake.setVariant("my-toggle", new Variant("b", null, true));
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), fake);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        Optional<fr.enimaloc.catapult.domain.ExperimentVariant> result = provider.getVariant(user, "my-toggle");

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isEqualTo("b");
        assertThat(result.get().isControl()).isFalse();
    }
}
