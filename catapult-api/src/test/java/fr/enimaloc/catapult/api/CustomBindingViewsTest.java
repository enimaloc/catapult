package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomBindingViewsTest {

    private final CustomBindingService service = mock(CustomBindingService.class);
    private final CustomBindingViews views = new CustomBindingViews(service);
    private final UserAccount user = new UserAccount();

    @Test
    void state_carriesLimit() {
        when(service.list(user)).thenReturn(List.of());
        when(service.active(user)).thenReturn(Optional.empty());

        when(service.limit(user)).thenReturn(OptionalInt.of(5));
        assertThat(views.state(user).limit()).isEqualTo(5);

        when(service.limit(user)).thenReturn(OptionalInt.empty());
        assertThat(views.state(user).limit()).isNull();
    }
}
