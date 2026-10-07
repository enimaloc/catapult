package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.service.binding.BindingDto;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import fr.enimaloc.catapult.service.settings.UserSettingsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The binding, settings, Steam token and account actions (see the sibling classes for the rest). */
class ApiChannelActionsControllerTest extends ApiChannelActionsTestSupport {

    private UserAccount owner;
    private UserAccount moderator;
    private final UUID bindingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        owner = registered("streamer");
        moderator = registered("mod");
        when(channelAccessService.canAccess(any(), eq(owner))).thenReturn(true);
    }

    private MockHttpServletRequestBuilder postAs(UserAccount user, String path, String json) {
        MockHttpServletRequestBuilder request = post("/api/channels/streamer" + path)
                .with(userJwt(user.getId())).with(csrf());
        return json == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private GameBinding storedBinding() {
        GameBinding binding = new GameBinding();
        binding.setId(bindingId);
        binding.setUser(owner);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceName("Valheim");
        binding.setStatus(GameBinding.Status.AUTO);
        when(bindingService.findBinding(owner, bindingId)).thenReturn(Optional.of(binding));
        return binding;
    }

    @Nested
    class Bindings {
        @Test
        void cclToggle_byAModerator_isAppliedAndPublished() throws Exception {
            GameBinding binding = storedBinding();

            mvc.perform(postAs(moderator, "/bindings/" + bindingId + "/ccl-toggle", "{\"enabled\":false}"))
                    .andExpect(status().isNoContent());

            verify(bindingService).toggleCclEnabled(owner, bindingId, false);
            verify(channelEventPublisher).bindingUpserted(owner.getId(), BindingDto.from(binding));
        }

        @Test
        void ignoredToggle() throws Exception {
            storedBinding();

            mvc.perform(postAs(moderator, "/bindings/" + bindingId + "/ignored-toggle", "{\"ignored\":true}"))
                    .andExpect(status().isNoContent());

            verify(bindingService).toggleIgnored(owner, bindingId, true);
            verify(channelEventPublisher).bindingUpserted(any(), any());
        }

        @Test
        void vanishedBinding_isNotPublished() throws Exception {
            mvc.perform(postAs(moderator, "/bindings/" + bindingId + "/ignored-toggle", "{\"ignored\":true}"))
                    .andExpect(status().isNoContent());

            verify(channelEventPublisher, never()).bindingUpserted(any(), any());
        }

        @Test
        void update_defaultsMissingCclsToNone() throws Exception {
            storedBinding();

            mvc.perform(postAs(moderator, "/bindings/" + bindingId, "{\"twitchGameId\":\"7\",\"twitchGameName\":\"Doom\"}"))
                    .andExpect(status().isNoContent());

            verify(bindingService).updateBinding(owner, bindingId, "7", "Doom", Set.of(), false);
        }

        @Test
        void update_withCcls() throws Exception {
            mvc.perform(postAs(moderator, "/bindings/" + bindingId,
                            "{\"twitchGameId\":\"7\",\"twitchGameName\":\"Doom\",\"ccls\":[\"Gore\"]}"))
                    .andExpect(status().isNoContent());

            verify(bindingService).updateBinding(owner, bindingId, "7", "Doom", Set.of("Gore"), false);
        }

        @Test
        void delete_isPublished() throws Exception {
            mvc.perform(postAs(moderator, "/bindings/" + bindingId + "/delete", null)).andExpect(status().isNoContent());

            verify(bindingService).deleteBinding(owner, bindingId);
            verify(channelEventPublisher).bindingDeleted(owner.getId(), bindingId);
        }

        @Test
        void inaccessibleChannel_isForbidden() throws Exception {
            when(channelAccessService.canAccess(moderator, owner)).thenReturn(false);

            mvc.perform(postAs(moderator, "/bindings/" + bindingId + "/delete", null)).andExpect(status().isForbidden());

            verifyNoInteractions(bindingService);
        }
    }

    @Nested
    class Settings {
        private UserSettings saved() {
            ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
            verify(userSettingsRepository).save(captor.capture());
            return captor.getValue();
        }

        @Test
        void ccl_replacesTheBlockedListOfExistingSettings() throws Exception {
            UserSettings existing = new UserSettings();
            existing.setBlockedCcls(new java.util.HashSet<>(Set.of("Old")));
            when(userSettingsRepository.findById(owner.getId())).thenReturn(Optional.of(existing));

            mvc.perform(postAs(moderator, "/settings/ccl", "{\"cclEnabled\":false,\"blockedCcls\":[\"Gore\"]}"))
                    .andExpect(status().isNoContent());

            assertThat(saved()).isSameAs(existing);
            assertThat(existing.isCclFeatureEnabled()).isFalse();
            assertThat(existing.getBlockedCcls()).containsExactly("Gore");
            verify(channelEventPublisher).settingsUpdated(owner.getId(), UserSettingsDto.from(existing));
        }

        @Test
        void tws_createsSettingsForAChannelWithoutAny() throws Exception {
            mvc.perform(postAs(owner, "/settings/tws", "{\"enabled\":true,\"blockedTws\":null}"))
                    .andExpect(status().isNoContent());

            UserSettings created = saved();
            assertThat(created.getUser()).isSameAs(owner);
            assertThat(created.isTwFeatureEnabled()).isTrue();
            assertThat(created.getBlockedTws()).isEmpty();
        }

        @Test
        void noGame_appliesTheDefaultRightAwayWhenNothingIsDetected() throws Exception {
            mvc.perform(postAs(owner, "/settings/no-game", """
                            {"twitchGameId":"1","twitchGameName":"Just Chatting","ccls":["Gore"],
                             "applyOnStreamStart":true,"applyOnNoGame":false,"applyOnStreamEnd":true}"""))
                    .andExpect(status().isNoContent());

            UserSettings settings = saved();
            assertThat(settings.getNoGameTwitchGameName()).isEqualTo("Just Chatting");
            assertThat(settings.getNoGameCcls()).containsExactly("Gore");
            assertThat(settings.isApplyDefaultOnStreamStart()).isTrue();
            assertThat(settings.isApplyDefaultOnNoGame()).isFalse();
            assertThat(settings.isApplyDefaultOnStreamEnd()).isTrue();
            verify(twitchService).resetToDefault(owner);
        }

        @Test
        void noGame_leavesTheCategoryAloneWhileAGameIsDetected() throws Exception {
            when(gameStateService.getLastKnownGame(owner)).thenReturn(Optional.of(
                    new fr.enimaloc.catapult.getter.DetectedGame("1", GameBinding.SourceType.STEAM, "Doom")));

            mvc.perform(postAs(owner, "/settings/no-game", """
                            {"ccls":null,"applyOnStreamStart":false,"applyOnNoGame":false,"applyOnStreamEnd":false}"""))
                    .andExpect(status().isNoContent());

            verify(twitchService, never()).resetToDefault(any());
        }

        @Test
        void incompleteFallback() throws Exception {
            mvc.perform(postAs(owner, "/settings/incomplete-fallback",
                            "{\"twitchGameId\":\"2\",\"twitchGameName\":\"Other\",\"ccls\":[\"Drugs\"]}"))
                    .andExpect(status().isNoContent());

            UserSettings settings = saved();
            assertThat(settings.getIncompleteFallbackTwitchGameId()).isEqualTo("2");
            assertThat(settings.getIncompleteFallbackCcls()).containsExactly("Drugs");
        }
    }

    @Nested
    class SteamPersonalToken {
        private final SteamProfileDto profile = new SteamProfileDto(true, false, false, false, 15L, true, true);

        @BeforeEach
        void stubProfile() {
            when(steamProfileDiagnostics.diagnose(owner)).thenReturn(profile);
            when(tokenEncryptionService.encrypt("KEY")).thenReturn("enc");
            when(tokenEncryptionService.decrypt("enc")).thenReturn("KEY");
        }

        @Test
        void save_encryptsAndPoolsTheTrimmedToken() throws Exception {
            mvc.perform(postAs(owner, "/settings/steam-personal-token", "{\"token\":\"  KEY  \",\"shared\":false}"))
                    .andExpect(status().isNoContent());

            assertThat(owner.getSteamPersonalToken()).isEqualTo("enc");
            assertThat(owner.isSteamTokenShared()).isFalse();
            verify(userAccountRepository).save(owner);
            verify(steamApiKeyRepository).deleteByOwner(owner);
            verify(steamApiKeyRepository).save(argThat((SteamApiKeyEntry key) ->
                    key.getOwner() == owner && key.isExclusive()));
            verify(channelEventPublisher).steamProfileChanged(owner.getId(), profile);
        }

        @Test
        void save_keyAlreadyPooled_isntPooledTwice() throws Exception {
            when(steamApiKeyRepository.existsById("KEY")).thenReturn(true);

            mvc.perform(postAs(owner, "/settings/steam-personal-token", "{\"token\":\"KEY\",\"shared\":true}"))
                    .andExpect(status().isNoContent());

            verify(steamApiKeyRepository, never()).save(any());
        }

        @Test
        void save_blankToken_isIgnored() throws Exception {
            mvc.perform(postAs(owner, "/settings/steam-personal-token", "{\"token\":\"  \",\"shared\":true}"))
                    .andExpect(status().isNoContent());

            verify(userAccountRepository, never()).save(any());
            verifyNoInteractions(channelEventPublisher);
        }

        @Test
        void save_isOwnerOnly() throws Exception {
            mvc.perform(postAs(moderator, "/settings/steam-personal-token", "{\"token\":\"KEY\",\"shared\":true}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void sharing_requiresAToken() throws Exception {
            mvc.perform(postAs(owner, "/settings/steam-personal-token/sharing", "{\"shared\":true}"))
                    .andExpect(status().isNoContent());

            verify(userAccountRepository, never()).save(any());
        }

        @Test
        void sharing_repoolsTheKeyWithItsNewSharing() throws Exception {
            owner.setSteamPersonalToken("enc");

            mvc.perform(postAs(owner, "/settings/steam-personal-token/sharing", "{\"shared\":true}"))
                    .andExpect(status().isNoContent());

            assertThat(owner.isSteamTokenShared()).isTrue();
            verify(steamApiKeyRepository).save(argThat((SteamApiKeyEntry key) -> !key.isExclusive()));
            verify(channelEventPublisher).steamProfileChanged(owner.getId(), profile);
        }

        @Test
        void delete_forgetsTheTokenAndUnpoolsIt() throws Exception {
            owner.setSteamPersonalToken("enc");
            owner.setSteamTokenShared(true);

            mvc.perform(postAs(owner, "/settings/steam-personal-token/delete", null)).andExpect(status().isNoContent());

            assertThat(owner.getSteamPersonalToken()).isNull();
            assertThat(owner.isSteamTokenShared()).isFalse();
            verify(steamApiKeyRepository).deleteByOwner(owner);
            verify(channelEventPublisher).steamProfileChanged(owner.getId(), profile);
        }
    }

    @Nested
    class Account {
        @Test
        void deletion_needsTheUsernameTyped_caseInsensitive() throws Exception {
            mvc.perform(postAs(owner, "/settings/delete-account", "{\"confirmUsername\":\"wrong\"}"))
                    .andExpect(status().isNoContent());
            verify(accountService, never()).initiateAccountDeletion(any());

            mvc.perform(postAs(owner, "/settings/delete-account", "{\"confirmUsername\":\"STREAMER\"}"))
                    .andExpect(status().isNoContent());
            verify(accountService).initiateAccountDeletion(owner);
        }

        @Test
        void deletion_isOwnerOnly() throws Exception {
            mvc.perform(postAs(moderator, "/settings/delete-account", "{\"confirmUsername\":\"streamer\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void cancelDeletion() throws Exception {
            mvc.perform(postAs(owner, "/settings/cancel-deletion", null)).andExpect(status().isNoContent());

            verify(accountService).cancelAccountDeletion(owner);
        }

        @Test
        void disconnect_providerIsCaseInsensitive_andPublished() throws Exception {
            mvc.perform(postAs(owner, "/settings/disconnect", "{\"provider\":\"xbox\"}")).andExpect(status().isNoContent());

            verify(accountService).disconnectProvider(owner, OAuthToken.Provider.XBOX);
            verify(channelEventPublisher).connectionChanged(owner.getId(), new ProviderConnectionsDto("XBOX", false, null));
        }
    }
}
