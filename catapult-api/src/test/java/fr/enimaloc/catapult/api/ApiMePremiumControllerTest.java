package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.premium.PremiumStatusDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.premium.PremiumService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiMePremiumControllerTest {

    private final ApiUserResolver resolver = mock(ApiUserResolver.class);
    private final PremiumService premium = mock(PremiumService.class);
    private final ApiMePremiumController controller = new ApiMePremiumController(resolver, premium);

    @Test
    void premium_isTheStatusOfTheAccountInThePath() {
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount();
        PremiumStatusDto status = new PremiumStatusDto(false, null, 2, 5, null);
        when(resolver.user(id)).thenReturn(account);
        when(premium.status(account)).thenReturn(status);

        assertThat(controller.premium(id)).isSameAs(status);
    }
}
