package fr.enimaloc.catapult.common.dto.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BungieCredentialsDtoTest {

    @Test
    void none_isNothingSet() {
        assertThat(BungieCredentialsData.none()).isEqualTo(new BungieCredentialsData(false, null, null, null));
    }

    @Test
    void theRequest_neverShowsItsSecrets() {
        BungieCredentialsRequest request = new BungieCredentialsRequest("12345", "the-secret", "the-key");

        assertThat(request.toString()).contains("12345").doesNotContain("the-secret").doesNotContain("the-key");
        assertThat(request.clientSecret()).isEqualTo("the-secret");
    }
}
