package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.BindingDto;
import fr.enimaloc.catapult.common.dto.ChannelDto;
import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.ObsData;
import fr.enimaloc.catapult.common.dto.UserSettingsDto;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockDataTest {

    private static final ChannelDto LIVE = MockPresets.CHANNELS.getFirst();
    private static final BindingDto STUNTBOOST = MockPresets.BINDINGS.get(0);
    private static final BindingDto CONTROL = MockPresets.BINDINGS.get(1);

    private static MockData session(List<BindingDto> bindings) {
        return new MockData(LIVE, bindings, MockPresets.DEFAULT_SETTINGS, List.of(),
                new MockData.SteamState(true, false, false, false, false, false), true, false,
                new LinkStateResponse("NONE", null, null), new ObsData(false, "127.0.0.1", 4455, false));
    }

    private static MockData session() {
        return session(List.of(STUNTBOOST, CONTROL));
    }

    private static BindingDto bindingOf(MockData data, BindingDto binding) {
        return data.getBinding().stream().filter(b -> b.id().equals(binding.id())).findFirst().orElseThrow();
    }

    @Nested
    class Factories {
        @Test
        void quickLaunchZero_isTheLiveChannelWithEveryChannelListed() {
            MockData data = MockData.fromJwt("0");

            assertThat(data.getChannelDto()).isEqualTo(LIVE);
            assertThat(data.getBinding()).containsExactly(STUNTBOOST);
            assertThat(data.getChannelList().channels()).isEqualTo(MockPresets.CHANNELS);
            assertThat(data.getUserSettings()).isEqualTo(MockPresets.DEFAULT_SETTINGS);
            assertThat(data.isBotEnabled()).isTrue();
            assertThat(data.isHasSteamProvider()).isTrue();
            assertThat(data.isHasXboxProvider()).isFalse();
        }

        @Test
        void quickLaunchOne_isTheOfflineChannelAlone() {
            MockData data = MockData.fromJwt("1");

            assertThat(data.getChannelDto()).isEqualTo(MockPresets.CHANNELS.get(1));
            assertThat(data.getBinding()).containsExactly(CONTROL);
            assertThat(data.getChannelList().channels()).containsExactly(MockPresets.CHANNELS.get(1));
        }

        @Test
        void quickLaunchBeyondThePresets_reusesTheLastOnes() {
            MockData data = MockData.fromJwt("9");

            assertThat(data.getChannelDto()).isEqualTo(MockPresets.CHANNELS.getLast());
            assertThat(data.getBinding()).containsExactly(MockPresets.BINDINGS.getLast());
        }

        @Test
        void quickLaunchCode_mustBeANumber() {
            assertThatThrownBy(() -> MockData.fromJwt("abc")).isInstanceOf(NumberFormatException.class);
        }

        @Test
        void jsonConfig_buildsTheDescribedSession() {
            MockData data = MockData.fromJwt("""
                    {"username": "custom", "twitchId": "42", "live": false, "botEnabled": false,
                     "steam": {"connected": false},
                     "xbox": {"connected": true},
                     "minecraft": {"status": "ACCEPTED", "minecraftName": "Steve"},
                     "binding": {"sourceType": "XBOX", "sourceName": "Halo", "status": "MANUAL", "ccls": ["Gore"]},
                     "settings": {"twFeatureEnabled": true, "blockedTws": ["spiders"]},
                     "ignored": "unknown fields are fine"}""");

            assertThat(data.getChannelDto().twitchUsername()).isEqualTo("custom");
            assertThat(data.getChannelDto().live()).isFalse();
            assertThat(data.isBotEnabled()).isFalse();
            assertThat(data.isHasSteam()).isFalse();
            assertThat(data.isHasXbox()).isTrue();
            assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("ACCEPTED", "Steve", null));
            assertThat(data.getPrimaryBinding().sourceName()).isEqualTo("Halo");
            assertThat(data.getPrimaryBinding().status()).isEqualTo("MANUAL");
            assertThat(data.getPrimaryBinding().ccls()).containsExactly("Gore");
            assertThat(data.getUserSettings().twFeatureEnabled()).isTrue();
            assertThat(data.getUserSettings().blockedTws()).containsExactly("spiders");
            assertThat(data.getChannelList().channels()).containsExactly(data.getChannelDto());
        }

        @Test
        void emptyJsonConfig_usesEveryDefault() {
            MockData data = MockData.fromJwt("{}");

            assertThat(data.getChannelDto().twitchUsername()).isEqualTo("enimaloc_stream");
            assertThat(data.getPrimaryBinding().sourceName()).isEqualTo("STUNTBOOST");
        }

        @Test
        void malformedJsonConfig_fallsBackToPresetZero() {
            assertThat(MockData.fromJwt("{not json").getChannelDto()).isEqualTo(LIVE);
        }

        @Test
        void random_givesDistinctSessions() {
            assertThat(MockData.random().getChannelDto().id()).isNotEqualTo(MockData.random().getChannelDto().id());
        }

        @Test
        void randomFrom_regeneratesTheSameSessionForASeed() {
            MockData first = MockData.randomFrom("token");
            MockData again = MockData.randomFrom("token");

            assertThat(again.getChannelDto()).isEqualTo(first.getChannelDto());
            assertThat(again.getBinding()).isEqualTo(first.getBinding());
            assertThat(again.isHasSteam()).isEqualTo(first.isHasSteam());
            assertThat(again.isHasXbox()).isEqualTo(first.isHasXbox());
            assertThat(MockData.randomFrom("other").getChannelDto()).isNotEqualTo(first.getChannelDto());
        }

        @Test
        void randomSessions_onlyHaveSteamDiagnosticsWhenConnected() {
            for (int seed = 0; seed < 50; seed++) {
                MockData data = MockData.randomFrom("seed-" + seed);
                if (!data.isHasSteam()) {
                    assertThat(data.isSteamProfilePrivate() || data.isSteamRateLimited() || data.isSteamOfflineMode())
                            .isFalse();
                }
            }
        }
    }

    @Nested
    class Page {
        @Test
        void ownerSeesEverySection() {
            ChannelPageData page = session().getPage(LIVE.twitchUsername(), null, null);

            assertThat(page.isOwner()).isTrue();
            assertThat(page.currentGame().bindingId()).isEqualTo(STUNTBOOST.id());
            assertThat(page.bindings().content()).containsExactly(STUNTBOOST, CONTROL);
            assertThat(page.bindings().totalPages()).isEqualTo(1);
            assertThat(page.steam()).isNotNull();
            assertThat(page.steam().profileCacheTtlMinutes()).isEqualTo(15L);
            assertThat(page.xbox().connected()).isFalse();
            assertThat(page.minecraft().status()).isEqualTo("NONE");
            assertThat(page.obs()).isEqualTo(new ObsData(false, "127.0.0.1", 4455, false));
            assertThat(page.exampleUuid()).isEqualTo("00000000-0000-0000-0000-000000000000");
        }

        @Test
        void otherViewersAreNotOwners() {
            assertThat(session().getPage("someone-else", null, null).isOwner()).isFalse();
        }

        @Test
        void filtersBindingsByStatusAndSourceIgnoringCase() {
            MockData data = session();
            data.replaceBinding("MANUAL", "XBOX", "Halo", "1", "Halo", false, true, Set.of(), true, false, Set.of());

            assertThat(data.getPage(LIVE.twitchUsername(), "manual", null).bindings().content())
                    .extracting(BindingDto::sourceName).containsExactly("Halo");
            assertThat(data.getPage(LIVE.twitchUsername(), null, "steam").bindings().content())
                    .containsExactly(CONTROL);
            assertThat(data.getPage(LIVE.twitchUsername(), "AUTO", "XBOX").bindings().content()).isEmpty();
            assertThat(data.getPage(LIVE.twitchUsername(), "AUTO", "XBOX").bindings().totalPages()).isZero();
        }

        @Test
        void unavailableProvidersAreLeftOut() {
            MockData data = new MockData(LIVE, List.of(), MockPresets.DEFAULT_SETTINGS, List.of(),
                    null, false, false, null, null);

            ChannelPageData page = data.getPage(LIVE.twitchUsername(), null, null);

            assertThat(page.steam()).isNull();
            assertThat(page.xbox()).isNull();
            assertThat(page.minecraft()).isNull();
            assertThat(page.obs()).isNull();
        }

        @Test
        void sessionWithoutBindings_hasNoDetectedGame() {
            MockData data = session(List.of());

            assertThat(data.getGameDto()).isNull();
            assertThat(data.getPage(LIVE.twitchUsername(), null, null).currentGame()).isNull();
            assertThat(data.getPrimaryBinding()).isNull();
        }

        @Test
        void channelUserMirrorsTheChannel() {
            assertThat(session().getChannelUser().twitchUsername()).isEqualTo(LIVE.twitchUsername());
            assertThat(session().getChannelUser().id()).isEqualTo(LIVE.id().toString());
        }
    }

    @Nested
    class BindingMutations {
        @Test
        void togglesOnlyTheTargetedBinding() {
            MockData data = session();

            data.setBindingIgnored(CONTROL.id(), true);
            data.setBindingCclEnabled(CONTROL.id(), false);
            data.setBindingTwEnabled(CONTROL.id(), false);

            assertThat(bindingOf(data, CONTROL).ignored()).isTrue();
            assertThat(bindingOf(data, CONTROL).cclEnabled()).isFalse();
            assertThat(bindingOf(data, CONTROL).twEnabled()).isFalse();
            assertThat(bindingOf(data, STUNTBOOST)).isEqualTo(STUNTBOOST);
        }

        @Test
        void updatesTheTwitchGameAndCcls() {
            MockData data = session();

            data.updateBindingGame(CONTROL.id(), "7", "Doom", Set.of("Gore"));

            BindingDto updated = bindingOf(data, CONTROL);
            assertThat(updated.twitchGameId()).isEqualTo("7");
            assertThat(updated.twitchGameName()).isEqualTo("Doom");
            assertThat(updated.ccls()).containsExactly("Gore");
            assertThat(updated.sourceName()).isEqualTo(CONTROL.sourceName());
        }

        @Test
        void customTriggerWarningsOverrideUntilReset() {
            MockData data = session();

            data.setBindingTws(STUNTBOOST.id(), Set.of("spiders"));
            assertThat(bindingOf(data, STUNTBOOST).twOverride()).isTrue();
            assertThat(bindingOf(data, STUNTBOOST).tws()).containsExactly("spiders");

            data.resetBindingTws(STUNTBOOST.id());
            assertThat(bindingOf(data, STUNTBOOST).twOverride()).isFalse();
            assertThat(bindingOf(data, STUNTBOOST).tws()).isEmpty();
        }

        @Test
        void deletesABinding() {
            MockData data = session();

            data.deleteBinding(STUNTBOOST.id());

            assertThat(data.getBinding()).containsExactly(CONTROL);
        }

        @Test
        void replacesThePrimaryBindingKeepingItsId() {
            MockData data = session();

            data.replaceBinding("MANUAL", "XBOX", "Halo", "1", "Halo", true, false, Set.of("a"), false, true, Set.of("b"));

            BindingDto primary = data.getPrimaryBinding();
            assertThat(primary.id()).isEqualTo(STUNTBOOST.id());
            assertThat(primary).isEqualTo(new BindingDto(STUNTBOOST.id(), "MANUAL", "XBOX", "Halo", "1", "Halo",
                    true, false, Set.of("a"), false, true, Set.of("b")));
            assertThat(data.getBinding()).hasSize(2).endsWith(CONTROL);
        }

        @Test
        void replacingWithoutAnyBinding_createsOne() {
            MockData data = session(List.of());

            data.replaceBinding("AUTO", "STEAM", "Valheim", "1", "Valheim", false, true, Set.of(), true, false, Set.of());

            assertThat(data.getBinding()).singleElement()
                    .extracting(BindingDto::id).isEqualTo(MockPresets.uuidOf("Valheim").toString());
        }
    }

    @Nested
    class SettingsMutations {
        @Test
        void eachCardOnlyChangesItsOwnFields() {
            MockData data = session();

            data.saveCclSettings(false, Set.of("Gore"));
            data.saveTwSettings(true, Set.of("spiders"));
            data.saveNoGameSettings("1", "Just Chatting", Set.of("Drugs"), true, false, true);
            data.saveIncompleteFallbackSettings("2", "Other", Set.of("Gambling"));

            UserSettingsDto settings = data.getUserSettings();
            assertThat(settings).isEqualTo(new UserSettingsDto(false, Set.of("Gore"),
                    "1", "Just Chatting", Set.of("Drugs"), true, false, true,
                    "2", "Other", Set.of("Gambling"),
                    MockPresets.AVAILABLE_CCLS, true, Set.of("spiders"), MockPresets.AVAILABLE_TWS));
        }
    }

    @Nested
    class Connections {
        @Test
        void minecraftLinkGoesPendingThenAcceptedOnSync() {
            MockData data = session();

            data.minecraftEnroll("Steve");
            assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("PENDING", "Steve", null));

            data.minecraftSync();
            assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("ACCEPTED", "Steve", null));

            data.minecraftDisconnect();
            assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("NONE", null, null));
        }

        @Test
        void syncOnlyAcceptsPendingOrRemovedLinks() {
            MockData data = session();

            data.minecraftSync();

            assertThat(data.getMinecraftLink().status()).isEqualTo("NONE");
        }

        @Test
        void steamPersonalTokenLifecycle() {
            MockData data = session();

            data.saveSteamToken(true);
            assertThat(data.getPage(LIVE.twitchUsername(), null, null).steam().hasPersonalToken()).isTrue();
            assertThat(data.getPage(LIVE.twitchUsername(), null, null).steam().tokenShared()).isTrue();

            data.setSteamTokenShared(false);
            assertThat(data.getPage(LIVE.twitchUsername(), null, null).steam().tokenShared()).isFalse();

            data.deleteSteamToken();
            assertThat(data.getPage(LIVE.twitchUsername(), null, null).steam().hasPersonalToken()).isFalse();
        }
    }

    @Nested
    class AdminMutators {
        @Test
        void changesTheChannel() {
            MockData data = session();

            data.setChannelLive(false);
            data.setChannelAvatarUrl("https://img");

            assertThat(data.getChannelDto().live()).isFalse();
            assertThat(data.getChannelDto().profileImageUrl()).isEqualTo("https://img");
            assertThat(data.getChannelDto().twitchUsername()).isEqualTo(LIVE.twitchUsername());
        }

        @Test
        void changesTheDetectedGameKeepingItsBinding() {
            MockData data = session();

            data.setDetectedGame("XBOX", "Halo");

            assertThat(data.getGameDto().bindingId()).isEqualTo(STUNTBOOST.id());
            assertThat(data.getGameDto().sourceName()).isEqualTo("Halo");
        }

        @Test
        void sessionWithoutBindings_canStillBeGivenADetectedGame() {
            MockData data = session(List.of());

            data.setDetectedGame("STEAM", "Valheim");

            assertThat(data.getGameDto().bindingId()).isNull();
            assertThat(data.getGameDto().sourceName()).isEqualTo("Valheim");
        }

        @Test
        void changesProvidersAndSteamDiagnostics() {
            MockData data = session();

            data.setProviders(false, false, true, true);
            data.setSteamDiagnostics(true, true, true, 60L);

            assertThat(data.isHasSteamProvider()).isFalse();
            assertThat(data.isHasXbox()).isTrue();
            assertThat(data.isSteamProfilePrivate()).isTrue();
            assertThat(data.isSteamRateLimited()).isTrue();
            assertThat(data.isSteamOfflineMode()).isTrue();
            assertThat(data.getSteamProfileCacheTtlMinutes()).isEqualTo(60L);
        }
    }
}
