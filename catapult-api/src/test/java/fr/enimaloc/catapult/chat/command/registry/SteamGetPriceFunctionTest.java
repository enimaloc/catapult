package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
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
        assertThat(fn.invoke(new Object[]{"1091500"})).isEqualTo("59,99€");
    }

    @Test
    void invokeReturnsNullWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamPrice("0")).thenReturn(Optional.empty());

        SteamGetPriceFunction fn = new SteamGetPriceFunction(gateway);
        assertThat(fn.invoke(new Object[]{"0"})).isNull();
    }
}
