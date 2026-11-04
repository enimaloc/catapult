package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.premium.PremiumStatusDto;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.premium.PremiumService;
import fr.enimaloc.catapult.service.premium.PremiumUiGate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiMePremiumControllerTest {

    private final ApiUserResolver resolver = mock(ApiUserResolver.class);
    private final PremiumService premium = mock(PremiumService.class);
    private final PremiumUiGate gate = mock(PremiumUiGate.class);
    private final ApiMePremiumController controller = new ApiMePremiumController(resolver, premium, gate);

    @Test
    void premium_isTheStatusOfTheAccountInThePath() {
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount();
        PremiumStatusDto status = new PremiumStatusDto(false, null, 2, 5, null);
        when(resolver.user(id)).thenReturn(account);
        when(gate.isOpenFor(account)).thenReturn(true);
        when(premium.status(account)).thenReturn(status);

        assertThat(controller.premium(id)).isSameAs(status);
    }

    @Test
    void premium_isRefusedWhilePremiumIsHiddenFromTheAccount() {
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount();
        when(resolver.user(id)).thenReturn(account);

        assertThatThrownBy(() -> controller.premium(id))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEATURE_DISABLED));
        verifyNoInteractions(premium);
    }
}
