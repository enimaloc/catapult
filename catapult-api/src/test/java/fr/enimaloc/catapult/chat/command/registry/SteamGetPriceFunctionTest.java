package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SteamGetPriceFunctionTest {

    @Test
    void invokeReturnsResolvedPrice() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamPrice("1091500")).thenReturn(Optional.of("59,99€"));

        SteamGetPriceFunction fn = new SteamGetPriceFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("steam");
        assertThat(fn.name()).isEqualTo("getPrice");
        assertThat(fn.parameterNames()).containsExactly("appId");
        assertThat(fn.invoke(null, new Object[]{"1091500"})).isEqualTo("59,99€");
    }

    @Test
    void invokeReturnsNullWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamPrice("0")).thenReturn(Optional.empty());

        SteamGetPriceFunction fn = new SteamGetPriceFunction(gateway);
        assertThat(fn.invoke(null, new Object[]{"0"})).isNull();
    }

    @Test
    void invokeIgnoresTheUserParameterEntirely() throws Exception {
        // Steam price is public, non-user-scoped data — the user argument must not affect the result.
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamPrice("1091500")).thenReturn(Optional.of("59,99€"));
        UserAccount someUser = new UserAccount();

        SteamGetPriceFunction fn = new SteamGetPriceFunction(gateway);
        assertThat(fn.invoke(someUser, new Object[]{"1091500"})).isEqualTo("59,99€");
    }
}
