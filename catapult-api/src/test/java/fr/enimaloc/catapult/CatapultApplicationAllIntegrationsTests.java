package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.getter.BattleNetGameGetter;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.getter.XboxGameGetter;
import fr.enimaloc.catapult.service.dtdd.DtddService;
import fr.enimaloc.catapult.service.minecraft.MinecraftFriendService;
import fr.enimaloc.catapult.service.twitch.IrcTwitchChatService;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(FullContextContainers.class)
@TestPropertySource(
        properties = {
            "xbox.enabled=true",
            "xbox.client-id=full-context-test",
            "xbox.client-secret=full-context-test",
            "minecraft.enabled=true",
            "minecraft.msa-client-id=full-context-test",
            "dtdd.enabled=true",
            "battlenet.enabled=true",
            "battlenet.client-id=full-context-test",
            "battlenet.client-secret=full-context-test",
            "app.chat.provider=irc"
        })
class CatapultApplicationAllIntegrationsTests extends FullContextTestBase {

    @Test
    void loadsEveryOptionalIntegration() throws Exception {
        assertThat(context.getBeansOfType(XboxGameGetter.class)).isNotEmpty();
        assertThat(context.getBeansOfType(MinecraftFriendService.class)).isNotEmpty();
        assertThat(context.getBeansOfType(MinecraftPresenceGetter.class)).isNotEmpty();
        assertThat(context.getBeansOfType(DtddService.class)).isNotEmpty();
        assertThat(context.getBeansOfType(BattleNetGameGetter.class)).isNotEmpty();
        assertThat(context.getBeansOfType(IrcTwitchChatService.class)).isNotEmpty();

        HttpResponse<String> response = get(managementPort, "/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
