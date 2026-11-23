package fr.enimaloc.catapult.common.dto.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OsuCredentialsDtoTest {

    @Test
    void none_isNothingSet() {
        assertThat(OsuCredentialsData.none()).isEqualTo(new OsuCredentialsData(false, null, null));
    }

    @Test
    void theRequest_neverShowsItsSecret() {
        OsuCredentialsRequest request = new OsuCredentialsRequest("12345", "the-secret");

        assertThat(request.toString()).contains("12345").doesNotContain("the-secret");
        assertThat(request.clientSecret()).isEqualTo("the-secret");
    }
}
