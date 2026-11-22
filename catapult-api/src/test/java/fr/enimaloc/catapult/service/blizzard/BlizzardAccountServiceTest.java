package fr.enimaloc.catapult.service.blizzard;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import fr.enimaloc.catapult.domain.blizzard.BlizzardWowCharacter;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardAccountRepository;
import fr.enimaloc.catapult.repository.blizzard.BlizzardWowCharacterRepository;
import fr.enimaloc.catapult.service.blizzard.BlizzardAccountService.ImportedCharacter;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlizzardAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final BlizzardAccountRepository accounts = mock(BlizzardAccountRepository.class);
    private final BlizzardWowCharacterRepository characters = mock(BlizzardWowCharacterRepository.class);
    private final BattleNetFriendLinkRepository links = mock(BattleNetFriendLinkRepository.class);
    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ChannelEventPublisher events = mock(ChannelEventPublisher.class);
    private final UserAccount user = new UserAccount();
    /** The character rows "in the database". */
    private final List<BlizzardWowCharacter> stored = new ArrayList<>();
    private BlizzardAccountService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        when(accounts.findById(user.getId())).thenReturn(Optional.empty());
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(new BattleNetFriendLink()));
        when(characters.findByUserId(user.getId())).thenAnswer(inv -> List.copyOf(stored));
        when(characters.saveAll(any())).thenAnswer(inv -> {
            Iterable<BlizzardWowCharacter> rows = inv.getArgument(0);
            rows.forEach(stored::add);
            return List.copyOf(stored);
        });
        service = new BlizzardAccountService(accounts, characters, links, cache, events,
                TransactionOperations.withoutTransaction(), "id", "secret", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BlizzardAccount existing() {
        BlizzardAccount row = new BlizzardAccount(user.getId());
        row.setRegion("eu");
        row.setUpdatedAt(NOW.minusSeconds(3600));
        when(accounts.findById(user.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private static ImportedCharacter imported(long id, String name, int level) {
        return new ImportedCharacter(BlizzardRegion.EU, id, "archimonde", "Archimonde", name, level, "Mage",
                "Alliance");
    }

    private static void assertProblem(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }

    @Test
    void regionMustBeOneOfFour() {
        assertProblem(() -> service.setRegion(user, "cn"), ErrorCode.BAD_REQUEST);
        assertProblem(() -> service.setRegion(user, null), ErrorCode.BAD_REQUEST);

        verify(accounts, never()).save(any());
    }

    @Test
    void regionNeedsABattleNetLink() {
        when(links.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertProblem(() -> service.setRegion(user, "eu"), ErrorCode.NOT_FOUND);

        verify(accounts, never()).save(any());
    }

    @Test
    void aRemovedLinkIsNoLinkForTheRegion() {
        BattleNetFriendLink removed = new BattleNetFriendLink();
        removed.setStatus(BattleNetFriendLink.Status.REMOVED);
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(removed));

        assertProblem(() -> service.setRegion(user, "eu"), ErrorCode.NOT_FOUND);

        verify(accounts, never()).save(any());
    }

    @Test
    void aLinkRemovedAfterTheFirstCheckStopsTheWrite() {
        BattleNetFriendLink removed = new BattleNetFriendLink();
        removed.setStatus(BattleNetFriendLink.Status.REMOVED);
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(new BattleNetFriendLink()))
                .thenReturn(Optional.of(removed));

        assertProblem(() -> service.setRegion(user, "eu"), ErrorCode.NOT_FOUND);

        verify(accounts, never()).save(any());
    }

    @Test
    void regionStartsTheRowAndEvictsAndTellsThePages() {
        service.setRegion(user, "KR");

        ArgumentCaptor<BlizzardAccount> saved = ArgumentCaptor.forClass(BlizzardAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getRegion()).isEqualTo("kr");
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        verify(cache).evictUser(user.getId());
        verify(events).blizzardAccountChanged(any(UUID.class), any(BlizzardStatsData.class));
    }

    @Test
    void aFailedPushDoesNotFailTheWrite() {
        doThrow(new IllegalStateException("redis down")).when(events).blizzardAccountChanged(any(), any());

        service.setRegion(user, "us");

        verify(accounts).save(any());
    }

    @Test
    void mainMustBeAnImportedCharacter() {
        existing();
        service.replaceWowCharacters(user.getId(), List.of(imported(1, "Alpha", 80)), NOW);

        assertProblem(() -> service.setWowMain(user, BlizzardRegion.EU, 99), ErrorCode.BAD_REQUEST);
        assertProblem(() -> service.setWowMain(user, BlizzardRegion.US, 1), ErrorCode.BAD_REQUEST);
    }

    @Test
    void mainWithoutAnAccountRowIsRefused() {
        assertProblem(() -> service.setWowMain(user, BlizzardRegion.EU, 1), ErrorCode.BAD_REQUEST);
    }

    @Test
    void mainIsSetToAListedCharacter() {
        BlizzardAccount row = existing();
        service.replaceWowCharacters(user.getId(), List.of(imported(1, "Alpha", 80), imported(2, "Beta", 70)), NOW);

        service.setWowMain(user, BlizzardRegion.EU, 2);

        assertThat(row.getWowMainRegion()).isEqualTo("eu");
        assertThat(row.getWowMainCharacterId()).isEqualTo(2L);
        verify(cache, times(2)).evictUser(user.getId());
    }

    @Test
    void reimportKeepsMainWhenStillListed() {
        BlizzardAccount row = existing();
        row.setWowMainRegion("eu");
        row.setWowMainCharacterId(1L);

        service.replaceWowCharacters(user.getId(), List.of(imported(2, "Beta", 80), imported(1, "Alpha", 10)), NOW);

        assertThat(row.getWowMainCharacterId()).isEqualTo(1L);
        assertThat(row.getWowImportedAt()).isEqualTo(NOW);
    }

    @Test
    void reimportFallsBackToHighestLevel() {
        BlizzardAccount row = existing();
        row.setWowMainRegion("eu");
        row.setWowMainCharacterId(1L);

        service.replaceWowCharacters(user.getId(),
                List.of(imported(2, "Beta", 70), imported(3, "Gamma", 80), imported(4, "Delta", 80)), NOW);

        assertThat(row.getWowMainRegion()).isEqualTo("eu");
        assertThat(row.getWowMainCharacterId()).isEqualTo(3L);
    }

    @Test
    void reimportReplacesTheList() {
        existing();
        service.replaceWowCharacters(user.getId(), List.of(imported(1, "Alpha", 80)), NOW);
        stored.clear();

        service.replaceWowCharacters(user.getId(), List.of(imported(1, "Alpha", 80), imported(1, "Alpha", 80),
                imported(2, "Beta", 70)), NOW);

        verify(characters, times(2)).deleteByUserId(user.getId());
        assertThat(stored).extracting(BlizzardWowCharacter::getCharacterId).containsExactly(1L, 2L);
    }

    @Test
    void anEmptyImportLeavesNoMain() {
        BlizzardAccount row = existing();
        row.setWowMainRegion("eu");
        row.setWowMainCharacterId(1L);

        service.replaceWowCharacters(user.getId(), List.of(), NOW);

        assertThat(row.getWowMainRegion()).isNull();
        assertThat(row.getWowMainCharacterId()).isNull();
        assertThat(row.getWowImportedAt()).isEqualTo(NOW);
    }

    @Test
    void namesAreStoredLowercase() {
        existing();

        service.replaceWowCharacters(user.getId(), List.of(imported(1, "Inspectordob", 80)), NOW);

        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.getName()).isEqualTo("inspectordob");
            assertThat(row.getDisplayName()).isEqualTo("Inspectordob");
            assertThat(row.getRegion()).isEqualTo("eu");
            assertThat(row.getUserId()).isEqualTo(user.getId());
        });
    }

    @Test
    void theFirstImportStartsTheRow() {
        // A member who never picked a region has no row yet: their first import starts it
        service.replaceWowCharacters(user.getId(), List.of(imported(1, "Alpha", 80)), NOW);

        ArgumentCaptor<BlizzardAccount> saved = ArgumentCaptor.forClass(BlizzardAccount.class);
        // The characters reference the row: it is saved first
        InOrder order = inOrder(accounts, characters);
        order.verify(accounts).save(saved.capture());
        order.verify(characters).saveAll(any());
        assertThat(saved.getValue().getRegion()).isEqualTo("eu");
        assertThat(saved.getValue().getWowMainCharacterId()).isEqualTo(1L);
        assertThat(saved.getValue().getWowImportedAt()).isEqualTo(NOW);
    }

    @Test
    void anEmptyFirstImportStartsNoRow() {
        service.replaceWowCharacters(user.getId(), List.of(), NOW);

        verify(characters, never()).saveAll(any());
        verify(accounts, never()).save(any());
    }

    @Test
    void replacingWithoutABattleNetLinkWritesNothing() {
        when(links.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertProblem(() -> service.replaceWowCharacters(user.getId(), List.of(imported(1, "Alpha", 80)), NOW),
                ErrorCode.NOT_FOUND);

        verify(characters, never()).deleteByUserId(any());
        verify(characters, never()).saveAll(any());
        verify(accounts, never()).save(any());
    }

    @Test
    void deletingTheCharactersClearsTheMainAndEvicts() {
        BlizzardAccount row = existing();
        row.setWowMainRegion("eu");
        row.setWowMainCharacterId(1L);
        row.setWowImportedAt(NOW);

        service.deleteWowCharacters(user);

        verify(characters).deleteByUserId(user.getId());
        assertThat(row.getWowMainRegion()).isNull();
        assertThat(row.getWowMainCharacterId()).isNull();
        assertThat(row.getWowImportedAt()).isNull();
        verify(cache).evictUser(user.getId());
    }

    @Test
    void deletingTheCharactersWithoutARowDoesNothing() {
        service.deleteWowCharacters(user);

        verifyNoInteractions(cache, events);
    }

    @Test
    void staleWriteIsRetriedThen409() {
        existing();
        when(accounts.save(any())).thenThrow(new ObjectOptimisticLockingFailureException(BlizzardAccount.class,
                user.getId()));

        assertProblem(() -> service.setRegion(user, "us"), ErrorCode.CONFLICT);

        verify(accounts, times(2)).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void aCrossedWriteIsRedoneOnceOnTheFreshRow() {
        existing();
        when(accounts.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.setRegion(user, "us");

        verify(accounts, times(2)).save(any());
        verify(events).blizzardAccountChanged(any(), any());
    }

    @Test
    void forgetDeletesTheRowAndEvicts() {
        existing();

        service.forget(user.getId());

        verify(characters).deleteByUserId(user.getId());
        verify(accounts).purgeByUserId(user.getId());
        verify(accounts, never()).save(any());
        verify(cache).evictUser(user.getId());
    }

    @Test
    void forgetWithoutARowStillEvicts() {
        service.forget(user.getId());

        verify(accounts).purgeByUserId(user.getId());
        verify(cache).evictUser(user.getId());
    }

    @Test
    void dataNeverCallsUpstream() {
        BlizzardAccount row = existing();
        row.setWowImportedAt(NOW);
        row.setWowMainRegion("eu");
        row.setWowMainCharacterId(1L);
        stored.add(character(1, "alpha", "Alpha", 80));
        stored.add(character(2, "beta", "Beta", 70));

        BlizzardStatsData data = service.data(user);

        assertThat(data.linked()).isTrue();
        assertThat(data.region()).isEqualTo("eu");
        assertThat(data.wowImported()).isTrue();
        assertThat(data.wowImportedAt()).isEqualTo(NOW);
        assertThat(data.available()).isTrue();
        assertThat(data.wowMainKey()).isEqualTo("eu:1");
        assertThat(data.characters()).extracting(BlizzardStatsData.WowCharacterOption::key)
                .containsExactly("eu:1", "eu:2");
        assertThat(data.characters().getFirst().label()).isEqualTo("Alpha — Archimonde (80, Mage)");
    }

    @Test
    void dataWithoutALinkOrARow() {
        when(links.findByUserId(user.getId())).thenReturn(Optional.empty());

        BlizzardStatsData data = service.data(user);

        assertThat(data.linked()).isFalse();
        assertThat(data.region()).isNull();
        assertThat(data.wowImported()).isFalse();
        assertThat(data.wowImportedAt()).isNull();
        assertThat(data.characters()).isEmpty();
        assertThat(data.wowMainKey()).isNull();
    }

    @Test
    void dataIgnoresARemovedLink() {
        BattleNetFriendLink removed = new BattleNetFriendLink();
        removed.setStatus(BattleNetFriendLink.Status.REMOVED);
        when(links.findByUserId(user.getId())).thenReturn(Optional.of(removed));

        assertThat(service.data(user).linked()).isFalse();
    }

    @Test
    void notAvailableWithoutTheClientCredentials() {
        BlizzardAccountService noSecret = new BlizzardAccountService(accounts, characters, links, cache, events,
                TransactionOperations.withoutTransaction(), "id", "", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(noSecret.data(user).available()).isFalse();
    }

    @Test
    void findReadsTheRow() {
        BlizzardAccount row = existing();

        assertThat(service.find(user.getId())).containsSame(row);
    }

    private BlizzardWowCharacter character(long id, String name, String display, int level) {
        BlizzardWowCharacter row = new BlizzardWowCharacter();
        row.setUserId(user.getId());
        row.setRegion("eu");
        row.setCharacterId(id);
        row.setRealmSlug("archimonde");
        row.setRealmName("Archimonde");
        row.setName(name);
        row.setDisplayName(display);
        row.setLevel(level);
        row.setClassName("Mage");
        row.setFaction("Alliance");
        return row;
    }
}
