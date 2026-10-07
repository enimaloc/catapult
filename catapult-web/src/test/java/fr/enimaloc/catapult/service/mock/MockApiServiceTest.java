package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.binding.BindingDeletedEvent;
import fr.enimaloc.catapult.event.binding.BindingIgnoredStateEvent;
import fr.enimaloc.catapult.event.binding.BindingUpdatedEvent;
import fr.enimaloc.catapult.event.binding.CclStateEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftEnrollEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSavedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSharedStateEvent;
import fr.enimaloc.catapult.event.tw.TwEnabledStateEvent;
import fr.enimaloc.catapult.event.tw.TwResetEvent;
import fr.enimaloc.catapult.event.tw.TwUpdatedEvent;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MockApiServiceTest {

    private final List<Object> events = new ArrayList<>();
    private final MockApiService service = new MockApiService(events::add);

    private MockData data;
    private String username;
    private String bindingId;

    /** Logs in through the mock flow and binds the resulting token to a fresh "browser session". */
    private void loginAs(String code) {
        String token = service.exchangeCode(code).token();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        data = service.getData();
        username = data.getChannelDto().twitchUsername();
        bindingId = data.getPrimaryBinding().id();
    }

    @BeforeEach
    void setUp() {
        loginAs("{}");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private BindingDto binding() {
        return data.getPrimaryBinding();
    }

    // --- sessions --------------------------------------------------------------------------------

    @Test
    void exchangeCode_mintsADistinctTokenPerLogin() {
        assertThat(service.exchangeCode("{}").token()).isNotEqualTo(service.exchangeCode("{}").token());
    }

    @Test
    void eachSessionGetsItsOwnData() {
        MockData first = service.getData();
        loginAs("{\"username\": \"other\"}");

        assertThat(service.getData()).isNotSameAs(first);
        assertThat(service.getData().getChannelDto().twitchUsername()).isEqualTo("other");
    }

    @Test
    void digitCodes_getRandomSessions() {
        loginAs("0");

        assertThat(service.getData()).isNotNull();
    }

    @Test
    void unknownToken_regeneratesTheSameSessionEveryTime() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, "lost-after-restart");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        MockData regenerated = service.getData();

        assertThat(regenerated.getChannelDto()).isEqualTo(MockData.randomFrom("lost-after-restart").getChannelDto());
        assertThat(service.getData()).isSameAs(regenerated);
    }

    @Test
    void noSession_hasNoData() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(service.getData()).isNull();

        RequestContextHolder.resetRequestAttributes();
        assertThat(service.getData()).isNull();
    }

    // --- reads -----------------------------------------------------------------------------------

    @Test
    void readsComeFromTheSessionsData() {
        assertThat(service.channelList()).isEqualTo(data.getChannelList());
        assertThat(service.channelPage(username, 0, null, null).channelUser()).isEqualTo(data.getChannelUser());
        assertThat(service.channelSettings(username)).isEqualTo(data.getUserSettings());
        assertThat(service.minecraftStatus(username)).isEqualTo(data.getMinecraftLink());
    }

    @Test
    void searchGames_matchesCategoryNamesIgnoringCase() {
        assertThat(service.searchGames(username, "VAL")).isEqualTo(List.of(Map.of("id", "508455", "name", "Valheim")));
        assertThat((List<?>) service.searchGames(username, "")).hasSize(MockPresets.CATEGORIES.size());
        assertThat((List<?>) service.searchGames(username, "zzz")).isEmpty();
    }

    @Test
    void dtddIsInert() {
        assertThat(service.dtddMappingStatus(username)).isEqualTo(new DtddMappingStatusDto(null, null, false, null));
        assertThat(service.dtddSearch("doom")).isEqualTo(new SearchResponse(List.of()));
        service.dtddValidate("1");
        service.dtddPropose("1", 2L, "r");
        assertThat(events).isEmpty();
    }

    @Test
    void noOpActions_changeNothing() {
        service.recheckGame(username);
        service.refreshSteamProfileCache(username);

        assertThat(events).isEmpty();
    }

    // --- mutations publish what changed ----------------------------------------------------------

    @Test
    void toggleBot() {
        service.toggleBot(username);

        assertThat(data.isBotEnabled()).isFalse();
        assertThat(events).containsExactly(new BotStateChangedEvent(username, false));
    }

    @Test
    void bindingToggles() {
        service.cclToggle(username, bindingId, false);
        service.ignoredToggle(username, bindingId, true);

        assertThat(binding().cclEnabled()).isFalse();
        assertThat(binding().ignored()).isTrue();
        assertThat(events).containsExactly(new CclStateEvent(username, bindingId, false),
                new BindingIgnoredStateEvent(username, bindingId, true));
    }

    @Test
    void updateBinding() {
        service.updateBinding(username, bindingId, "7", "Doom", Set.of("Gore"));

        assertThat(binding().twitchGameName()).isEqualTo("Doom");
        assertThat(events).containsExactly(new BindingUpdatedEvent(username, bindingId, "7", "Doom", Set.of("Gore")));
    }

    @Test
    void deleteBinding() {
        service.deleteBinding(username, bindingId);

        assertThat(data.getBinding()).isEmpty();
        assertThat(events).containsExactly(new BindingDeletedEvent(username, bindingId));
    }

    @Test
    void triggerWarnings_areAttributedToTheSessionsChannel() {
        service.saveTws(bindingId, Set.of("spiders"));
        service.toggleTwEnabled(bindingId, false);
        service.resetTws(bindingId);

        assertThat(binding().twEnabled()).isFalse();
        assertThat(binding().twOverride()).isFalse();
        assertThat(events).containsExactly(new TwUpdatedEvent(username, bindingId, Set.of("spiders")),
                new TwEnabledStateEvent(username, bindingId, false),
                new TwResetEvent(username, bindingId));
    }

    @Test
    void steamToken() {
        service.saveSteamToken(username, "KEY", true);
        service.steamTokenSharing(username, false);
        service.deleteSteamToken(username);

        assertThat(events).containsExactly(new SteamTokenSavedEvent(username, true),
                new SteamTokenSharedStateEvent(username, false), new SteamTokenDeletedEvent(username));
    }

    @Test
    void minecraftLink() {
        service.minecraftEnroll(username, "Steve");
        service.minecraftSync(username);
        service.minecraftDisconnect(username);

        assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("NONE", null, null));
        assertThat(events).containsExactly(new MinecraftEnrollEvent(username, "PENDING", "Steve"),
                new MinecraftSyncEvent(username, "ACCEPTED", "Steve"), new MinecraftDisconnectedEvent(username));
    }

    @Test
    void settings_areSavedWithoutEvents() {
        service.saveCclSettings(username, false, Set.of("Gore"));
        service.saveTwSettings(username, true, Set.of("spiders"));
        service.saveNoGameSettings(username, "1", "Just Chatting", Set.of(), true, true, false);
        service.saveIncompleteFallbackSettings(username, "2", "Other", Set.of("Drugs"));

        assertThat(data.getUserSettings().cclFeatureEnabled()).isFalse();
        assertThat(data.getUserSettings().twFeatureEnabled()).isTrue();
        assertThat(data.getUserSettings().noGameTwitchGameName()).isEqualTo("Just Chatting");
        assertThat(data.getUserSettings().incompleteFallbackCcls()).containsExactly("Drugs");
        assertThat(events).isEmpty();
    }
}
