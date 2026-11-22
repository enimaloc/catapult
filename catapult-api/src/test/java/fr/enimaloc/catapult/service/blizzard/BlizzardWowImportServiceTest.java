package fr.enimaloc.catapult.service.blizzard;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.service.blizzard.BlizzardAccountService.ImportedCharacter;
import fr.enimaloc.catapult.service.blizzard.BlizzardImportRefusedException.Reason;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardClient;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardLocale;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardUnavailableException;
import fr.enimaloc.catapult.service.enrichment.blizzard.WowUserCharacter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlizzardWowImportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String TOKEN = "user-token";
    private static final long BNET_ID = 123456L;

    @Mock private BlizzardClient client;
    @Mock private BlizzardAccountService accounts;
    @Mock private BattleNetFriendLinkRepository links;

    private final UserAccount user = new UserAccount();
    private BlizzardWowImportService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setBnetAccountId(BNET_ID);
        link.setStatus(BattleNetFriendLink.Status.ACCEPTED);
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(link));
        when(client.userAccountId(TOKEN)).thenReturn(BNET_ID);
        for (BlizzardRegion region : BlizzardRegion.values()) {
            when(client.wowUserCharacters(eq(region), eq(TOKEN), anyString())).thenReturn(List.of());
        }
        service = new BlizzardWowImportService(client, accounts, links, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WowUserCharacter character(long id, String name, int level) {
        return new WowUserCharacter(id, name, "ravencrest", "Ravencrest", "Warrior", "Horde", level);
    }

    @SuppressWarnings("unchecked")
    private List<ImportedCharacter> replaced() {
        ArgumentCaptor<List<ImportedCharacter>> captor = ArgumentCaptor.forClass(List.class);
        verify(accounts).replaceWowCharacters(eq(user.getId()), captor.capture(), eq(NOW));
        return captor.getValue();
    }

    @Test
    void importsAllFourRegions() {
        when(client.wowUserCharacters(eq(BlizzardRegion.EU), eq(TOKEN), anyString()))
                .thenReturn(List.of(character(1, "Inspectordob", 80), character(2, "Altchar", 12)));
        when(client.wowUserCharacters(eq(BlizzardRegion.US), eq(TOKEN), anyString()))
                .thenReturn(List.of(character(3, "Yankee", 70)));
        // KR: the client already turned the recognised 404 into an empty list; TW lists none.

        int count = service.importFor(user, TOKEN, Locale.FRENCH);

        assertThat(count).isEqualTo(3);
        for (BlizzardRegion region : BlizzardRegion.values()) {
            verify(client).wowUserCharacters(region, TOKEN, BlizzardLocale.of(Locale.FRENCH, region));
        }
        List<ImportedCharacter> imported = replaced();
        assertThat(imported).extracting(ImportedCharacter::region)
                .containsExactly(BlizzardRegion.EU, BlizzardRegion.EU, BlizzardRegion.US);
        assertThat(imported.getFirst()).isEqualTo(new ImportedCharacter(BlizzardRegion.EU, 1, "ravencrest",
                "Ravencrest", "Inspectordob", 80, "Warrior", "Horde"));
    }

    @Test
    void oneFailedRegionImportsNothing() {
        when(client.wowUserCharacters(eq(BlizzardRegion.EU), eq(TOKEN), anyString()))
                .thenReturn(List.of(character(1, "Inspectordob", 80)));
        when(client.wowUserCharacters(eq(BlizzardRegion.US), eq(TOKEN), anyString()))
                .thenThrow(new BlizzardUnavailableException("Blizzard answered 500"));

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH))
                .isInstanceOf(BlizzardUnavailableException.class);

        verify(accounts, never()).replaceWowCharacters(any(), any(), any());
    }

    @Test
    void importNeedsTheBattleNetLink() {
        when(links.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH)).isInstanceOfSatisfying(
                BlizzardImportRefusedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_LINKED));

        verify(client, never()).userAccountId(anyString());
        verify(client, never()).wowUserCharacters(any(), anyString(), anyString());
        verify(accounts, never()).replaceWowCharacters(any(), any(), any());
    }

    @Test
    void aRemovedLinkIsNoLink() {
        BattleNetFriendLink removed = new BattleNetFriendLink();
        removed.setBnetAccountId(BNET_ID);
        removed.setStatus(BattleNetFriendLink.Status.REMOVED);
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(removed));

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH)).isInstanceOfSatisfying(
                BlizzardImportRefusedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_LINKED));

        verify(client, never()).wowUserCharacters(any(), anyString(), anyString());
    }

    @Test
    void anotherBattleNetAccountImportsNothing() {
        when(client.userAccountId(TOKEN)).thenReturn(999L);

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH)).isInstanceOfSatisfying(
                BlizzardImportRefusedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.OTHER_ACCOUNT));

        verify(client, never()).wowUserCharacters(any(), anyString(), anyString());
        verify(accounts, never()).replaceWowCharacters(any(), any(), any());
    }

    @Test
    void anUnknownAccountIdImportsNothing() {
        when(client.userAccountId(TOKEN)).thenThrow(new BlizzardUnavailableException("Blizzard answered 401"));

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH))
                .isInstanceOf(BlizzardUnavailableException.class);

        verify(client, never()).wowUserCharacters(any(), anyString(), anyString());
        verify(accounts, never()).replaceWowCharacters(any(), any(), any());
    }

    @Test
    void aLinkRemovedBeforeTheWriteIsARefusal() {
        doThrow(new ApiProblem(ErrorCode.NOT_FOUND, "Battle.net is not linked"))
                .when(accounts).replaceWowCharacters(any(), any(), any());

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH)).isInstanceOfSatisfying(
                BlizzardImportRefusedException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_LINKED));
    }

    @Test
    void theTokenIsNeverInTheExceptionMessages() {
        when(client.wowUserCharacters(eq(BlizzardRegion.TW), eq(TOKEN), anyString()))
                .thenThrow(new BlizzardUnavailableException("Blizzard answered 503"));

        assertThatThrownBy(() -> service.importFor(user, TOKEN, Locale.FRENCH))
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(TOKEN));
    }
}
