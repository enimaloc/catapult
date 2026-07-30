package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetInvolvedCompaniesFunctionTest {

    private static final List<Map<String, Object>> COMPANIES = List.of(Map.of(
        "name", "Riot Games", "developer", true, "publisher", true, "supporting", false, "porting", false));

    @Test
    void invokeResolvesIdFromEitherABareIdOrTheGameObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbInvolvedCompanies("1234")).thenReturn(Optional.of(COMPANIES));

        IgdbGetInvolvedCompaniesFunction fn = new IgdbGetInvolvedCompaniesFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getInvolvedCompanies");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(COMPANIES);
        assertThat(fn.invoke(null, new Object[]{Map.of("id", "1234")})).isEqualTo(COMPANIES);
    }

    @Test
    void invokeReturnsEmptyListWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbInvolvedCompanies("9999")).thenReturn(Optional.empty());

        IgdbGetInvolvedCompaniesFunction fn = new IgdbGetInvolvedCompaniesFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(List.of());
    }
}
