package fr.enimaloc.catapult.service.social;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkOrderRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.domain.social.SocialLink;
import fr.enimaloc.catapult.repository.social.SocialLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static fr.enimaloc.catapult.common.dto.admin.SocialPlatform.DISCORD;
import static fr.enimaloc.catapult.common.dto.admin.SocialPlatform.GITHUB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialLinkServiceTest {
    @Mock SocialLinkRepository repository;
    SocialLinkService service;

    @BeforeEach
    void setUp() {
        service = new SocialLinkService(repository);
    }

    static SocialLink link(long id, int position, boolean visible) {
        SocialLink l = new SocialLink();
        l.setId(id);
        l.setPlatform(GITHUB);
        l.setUrl("https://github.com/x" + id);
        l.setVisible(visible);
        l.setPosition(position);
        return l;
    }

    static SocialLink saved(SocialLink l) {
        l.setId(1L);
        return l;
    }

    static void assertStatus(Throwable t, int code) {
        assertThat(((ResponseStatusException) t).getStatusCode().value()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://x", "javascript:alert(1)", "ftp://x", " https://x", "https://", "https://a b",
            "https://a\nb", "data:text/html,https://x"})
    void rejectsNonHttpsUrls(String url) {
        assertThatThrownBy(() -> service.create(new SocialLinkRequest(DISCORD, null, url, true)))
                .satisfies(t -> assertStatus(t, 400));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAMissingPlatformAUrlTooLongOrMissing() {
        for (SocialLinkRequest request : Arrays.asList(
                null,
                new SocialLinkRequest(null, null, "https://x", true),
                new SocialLinkRequest(DISCORD, null, null, true),
                new SocialLinkRequest(DISCORD, null, "https://" + "a".repeat(2041), true))) {
            assertThatThrownBy(() -> service.create(request)).satisfies(t -> assertStatus(t, 400));
        }
        verify(repository, never()).save(any());
    }

    @Test
    void acceptsHttpsCaseInsensitively() {
        when(repository.findFirstByOrderByPositionDesc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> {
            SocialLink l = i.getArgument(0);
            l.setId(4L);
            return l;
        });
        SocialLinkDto created = service.create(new SocialLinkRequest(DISCORD, "Our Discord", "HTTPS://discord.gg/x", true));
        assertThat(created).isEqualTo(new SocialLinkDto(4, DISCORD, "Our Discord", "HTTPS://discord.gg/x", true, 0));
    }

    @Test
    void rejectsLongLabel() {
        assertThatThrownBy(() -> service.create(new SocialLinkRequest(DISCORD, "a".repeat(65), "https://x", true)))
                .satisfies(t -> assertStatus(t, 400));
    }

    @Test
    void acceptsALabelOf64AndStoresABlankOneAsNone() {
        when(repository.findFirstByOrderByPositionDesc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> saved(i.getArgument(0)));
        assertThat(service.create(new SocialLinkRequest(DISCORD, "a".repeat(64), "https://x", true)).label())
                .hasSize(64);
        assertThat(service.create(new SocialLinkRequest(DISCORD, "  ", "https://x", true)).label()).isNull();
    }

    @Test
    void createsAtTheEnd() {
        when(repository.findFirstByOrderByPositionDesc()).thenReturn(Optional.of(link(1, 6, true)));
        when(repository.save(any())).thenAnswer(i -> saved(i.getArgument(0)));
        assertThat(service.create(new SocialLinkRequest(GITHUB, null, "https://github.com/y", false)).position())
                .isEqualTo(7);
    }

    @Test
    void updateReplacesTheLinkKeepingItsPosition() {
        SocialLink mine = link(1, 3, true);
        when(repository.findById(1L)).thenReturn(Optional.of(mine));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.update(1, new SocialLinkRequest(DISCORD, "d", "https://discord.gg/y", false)))
                .isEqualTo(new SocialLinkDto(1, DISCORD, "d", "https://discord.gg/y", false, 3));
    }

    @Test
    void updateRefusesABadUrlAndAnUnknownLinkIs404() {
        when(repository.findById(1L)).thenReturn(Optional.of(link(1, 0, true)));
        assertThatThrownBy(() -> service.update(1, new SocialLinkRequest(DISCORD, null, "http://x", true)))
                .satisfies(t -> assertStatus(t, 400));
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(9, new SocialLinkRequest(DISCORD, null, "https://x", true)))
                .satisfies(t -> assertStatus(t, 404));
        assertThatThrownBy(() -> service.delete(9)).satisfies(t -> assertStatus(t, 404));
    }

    @Test
    void deleteRemovesTheLink() {
        SocialLink mine = link(1, 0, true);
        when(repository.findById(1L)).thenReturn(Optional.of(mine));
        service.delete(1);
        verify(repository).delete(mine);
    }

    @Test
    void reorderSetsPositions() {
        List<SocialLink> links = new ArrayList<>(List.of(link(1, 0, true), link(2, 1, true), link(3, 2, true)));
        when(repository.findAllByOrderByPositionAscIdAsc()).thenReturn(links);
        when(repository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.reorder(new SocialLinkOrderRequest(List.of(3L, 1L, 2L))))
                .extracting(SocialLinkDto::id, SocialLinkDto::position)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3L, 0), org.assertj.core.groups.Tuple.tuple(1L, 1),
                        org.assertj.core.groups.Tuple.tuple(2L, 2));
    }

    @Test
    void reorderRefusesAnIncompleteList() {
        when(repository.findAllByOrderByPositionAscIdAsc()).thenReturn(List.of(link(1, 0, true), link(2, 1, true)));
        for (SocialLinkOrderRequest request : Arrays.asList(
                new SocialLinkOrderRequest(List.of(1L)),
                new SocialLinkOrderRequest(List.of(1L, 1L)),
                new SocialLinkOrderRequest(List.of(1L, 3L)),
                new SocialLinkOrderRequest(List.of(1L, 2L, 2L)),
                new SocialLinkOrderRequest(null),
                null)) {
            assertThatThrownBy(() -> service.reorder(request)).satisfies(t -> assertStatus(t, 400));
        }
        verify(repository, never()).saveAll(any());
    }

    @Test
    void publicListIsVisibleSortedOnly() {
        when(repository.findByVisibleTrueOrderByPositionAscIdAsc()).thenReturn(List.of(link(2, 0, true)));
        assertThat(service.listVisible()).extracting(SocialLinkDto::id).containsExactly(2L);
        verify(repository, never()).findAllByOrderByPositionAscIdAsc();
    }

    @Test
    void adminListHoldsHiddenLinksToo() {
        when(repository.findAllByOrderByPositionAscIdAsc()).thenReturn(List.of(link(1, 0, false), link(2, 1, true)));
        assertThat(service.listAll()).extracting(SocialLinkDto::visible).containsExactly(false, true);
    }
}
