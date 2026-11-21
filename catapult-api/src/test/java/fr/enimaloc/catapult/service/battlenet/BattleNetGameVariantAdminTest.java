package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetGameVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BattleNetGameVariantAdminTest {

    private final BattleNetGameVariantRepository repository = mock(BattleNetGameVariantRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final BattleNetGameVariants variants = mock(BattleNetGameVariants.class);
    private final BattleNetGameVariantAdmin admin = new BattleNetGameVariantAdmin(repository, users, variants);

    private UserAccount adminAccount;

    @BeforeEach
    void setUp() {
        adminAccount = new UserAccount();
        adminAccount.setId(UUID.randomUUID());
        adminAccount.setTwitchUsername("the_admin");
        when(repository.save(any(BattleNetGameVariant.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static BattleNetGameVariantRequest forever(String... patterns) {
        return new BattleNetGameVariantRequest("WoW", "Forever", "PREFIX", List.of(patterns),
                "World of Warcraft: Forever", false);
    }

    private static HttpStatus statusOf(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    private BattleNetGameVariant stored() {
        BattleNetGameVariant variant = new BattleNetGameVariant();
        variant.setId(UUID.randomUUID());
        variant.setFourcc("WoW");
        variant.setVariantKey("Forever");
        variant.setMatchType(BattleNetGameVariant.MatchType.PREFIX);
        variant.setPatterns(List.of("Forever - "));
        variant.setDisplayName("World of Warcraft: Forever");
        when(repository.findById(variant.getId())).thenReturn(Optional.of(variant));
        return variant;
    }

    @Test
    void createStoresTheRuleAndInvalidatesTheCache() {
        BattleNetGameVariantDto dto = admin.create(forever("Forever - "), adminAccount);

        assertThat(dto.fourcc()).isEqualTo("WoW");
        assertThat(dto.matchType()).isEqualTo("PREFIX");
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.updatedBy()).isEqualTo("the_admin");
        verify(repository).save(any(BattleNetGameVariant.class));
        verify(variants).invalidate();
    }

    @Test
    void createKeepsTrailingSpaceOfAPattern() {
        assertThat(admin.create(forever("Forever - ", "Für immer - \r"), adminAccount).patterns())
                .containsExactly("Forever - ", "Für immer - ");
    }

    @Test
    void dtoCarriesTheSourceId() {
        assertThat(admin.create(forever("Forever - "), adminAccount).sourceId()).isEqualTo("WoW:Forever");
    }

    @Test
    void createRejectsABadKey() {
        BattleNetGameVariantRequest request = new BattleNetGameVariantRequest("WoW", "For ever", "PREFIX",
                List.of("Forever - "), "World of Warcraft: Forever", false);

        assertThatThrownBy(() -> admin.create(request, adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBlankPatterns() {
        assertThatThrownBy(() -> admin.create(forever(" "), adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> admin.create(forever(), adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createRejectsAnUnknownMatchType() {
        BattleNetGameVariantRequest request = new BattleNetGameVariantRequest("WoW", "Forever", "REGEX",
                List.of("Forever - "), "World of Warcraft: Forever", false);

        assertThatThrownBy(() -> admin.create(request, adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createRejectsADuplicate() {
        when(repository.existsByFourccAndVariantKey("WoW", "Forever")).thenReturn(true);

        assertThatThrownBy(() -> admin.create(forever("Forever - "), adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void updateChangesThePatternsAndEnables() {
        BattleNetGameVariant variant = stored();
        BattleNetGameVariantRequest request = new BattleNetGameVariantRequest("WoW", "Forever", "CONTAINS",
                List.of("Forever - ", "Für immer - "), "WoW: Forever", true);

        BattleNetGameVariantDto dto = admin.update(variant.getId(), request, adminAccount);

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.matchType()).isEqualTo("CONTAINS");
        assertThat(dto.patterns()).containsExactly("Forever - ", "Für immer - ");
        assertThat(dto.displayName()).isEqualTo("WoW: Forever");
        verify(variants).invalidate();
    }

    @Test
    void updateCannotChangeTheKey() {
        BattleNetGameVariant variant = stored();
        BattleNetGameVariantRequest request = new BattleNetGameVariantRequest("WoW", "Classic", "PREFIX",
                List.of("Forever - "), "World of Warcraft: Forever", true);

        assertThatThrownBy(() -> admin.update(variant.getId(), request, adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateUnknownIdIs404() {
        assertThatThrownBy(() -> admin.update(UUID.randomUUID(), forever("Forever - "), adminAccount))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteInvalidatesTheCache() {
        BattleNetGameVariant variant = stored();

        admin.delete(variant.getId());

        verify(repository).delete(variant);
        verify(variants).invalidate();
    }

    @Test
    void deleteUnknownIdIs404() {
        assertThatThrownBy(() -> admin.delete(UUID.randomUUID()))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
