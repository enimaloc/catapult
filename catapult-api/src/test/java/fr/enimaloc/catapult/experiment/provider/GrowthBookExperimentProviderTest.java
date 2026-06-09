package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GrowthBookExperimentProviderTest {

    private ExperimentProviderProperties buildProps() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        ExperimentProviderProperties.GrowthBook gb = new ExperimentProviderProperties.GrowthBook();
        gb.setApiHost("https://app.growthbook.io");
        gb.setClientKey("sdk-test");
        gb.setApiKey("secret");
        props.setGrowthbook(gb);
        return props;
    }

    @Test
    void typeIsGrowthbook() {
        RestClient.Builder builder = RestClient.builder();
        GrowthBookExperimentProvider provider = new GrowthBookExperimentProvider(buildProps(), builder.build());
        assertThat(provider.type()).isEqualTo("growthbook");
    }

    @Test
    void adminUrlFromConfig() {
        RestClient.Builder builder = RestClient.builder();
        GrowthBookExperimentProvider provider = new GrowthBookExperimentProvider(buildProps(), builder.build());
        assertThat(provider.adminUrl()).contains("https://app.growthbook.io");
    }

    @Test
    void getVariantReturnsEmptyOnEmptyFeatureSet() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://app.growthbook.io/api/features/sdk-test"))
            .andRespond(withSuccess("{\"status\":200,\"features\":{}}", MediaType.APPLICATION_JSON));

        GrowthBookExperimentProvider provider = new GrowthBookExperimentProvider(buildProps(), builder.build());
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        Optional<fr.enimaloc.catapult.domain.ExperimentVariant> result = provider.getVariant(user, "missing-exp");
        assertThat(result).isEmpty();
    }
}
