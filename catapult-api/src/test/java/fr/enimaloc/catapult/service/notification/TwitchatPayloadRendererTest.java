package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchatPayloadRendererTest {

    private final List<TwitchatActionType> minted = new ArrayList<>();

    private TwitchatPayloadRenderer renderer(Set<TwitchatActionType> applicable) {
        return new TwitchatPayloadRenderer(Map.of("gameName", "Celeste", "baseUrl", "https://c.test"), applicable,
                type -> { minted.add(type); return "tok-" + type.name(); });
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    @Test
    void substitutesVariablesInEveryString_leavingUnknownOnes() {
        TwitchatPayload payload = renderer(Set.of()).render("SEND_MESSAGE",
                map("message", "Now {{gameName}} {{nope}}", "nested", map("list", List.of("{{ gameName }}", 3)))).orElseThrow();

        assertThat(payload.action()).isEqualTo("SEND_MESSAGE");
        assertThat(payload.data()).isEqualTo(map("message", "Now Celeste {{nope}}", "nested", map("list", List.of("Celeste", 3))));
    }

    @Test
    void chatMessage_dropsOnlyTheButtonsReferencingAnInapplicableAction() {
        Map<String, Object> data = map("message", "Changed", "actions", List.of(
                map("label", "Revert", "url", "{{baseUrl}}/a/{{action:REVERT_CATEGORY}}"),
                map("label", "Off", "url", "{{baseUrl}}/a/{{action:DISABLE_BOT}}"),
                map("label", "Docs", "url", "https://docs.test")));

        TwitchatPayload payload = renderer(Set.of(TwitchatActionType.DISABLE_BOT)).render("CUSTOM_CHAT_MESSAGE", data).orElseThrow();

        assertThat(payload.data().get("actions")).isEqualTo(List.of(
                map("label", "Off", "url", "https://c.test/a/tok-DISABLE_BOT"),
                map("label", "Docs", "url", "https://docs.test")));
        assertThat(minted).containsExactly(TwitchatActionType.DISABLE_BOT);
    }

    @Test
    void otherPayload_referencingAnInapplicableAction_isSkippedWhole() {
        assertThat(renderer(Set.of()).render("SEND_MESSAGE", map("message", "!revert {{action:REVERT_CATEGORY}}"))).isEmpty();
        assertThat(minted).isEmpty();
    }

    @Test
    void malformedOrUnknownActionPlaceholder_isTreatedAsInapplicable() {
        TwitchatPayloadRenderer renderer = renderer(Set.of(TwitchatActionType.values()));
        assertThat(renderer.render("SEND_MESSAGE", map("message", "{{ action : revert_category }}"))).isEmpty();
        assertThat(renderer.render("SEND_MESSAGE", map("message", "{{action:LAUNCH_ROCKET}}"))).isEmpty();
    }

    @Test
    void aTokenIsMintedOncePerType_andSharedAcrossPayloads() {
        TwitchatPayloadRenderer renderer = renderer(Set.of(TwitchatActionType.DISABLE_BOT));
        renderer.render("SEND_MESSAGE", map("message", "{{action:DISABLE_BOT}} {{action:DISABLE_BOT}}"));
        TwitchatPayload second = renderer.render("SEND_MESSAGE", map("message", "{{action:DISABLE_BOT}}")).orElseThrow();

        assertThat(second.data().get("message")).isEqualTo("tok-DISABLE_BOT");
        assertThat(minted).containsExactly(TwitchatActionType.DISABLE_BOT);
    }

    @Test
    void doesNotMutateTheInput() {
        Map<String, Object> data = map("message", "{{gameName}}");
        renderer(Set.of()).render("SEND_MESSAGE", data);
        assertThat(data.get("message")).isEqualTo("{{gameName}}");
    }
}
