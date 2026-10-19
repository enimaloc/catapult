package fr.enimaloc.catapult.service.meta;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.domain.meta.MetaTag;
import fr.enimaloc.catapult.repository.meta.MetaTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME;
import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaTagServiceTest {
    static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Mock MetaTagRepository repository;
    MetaTagService service;

    @BeforeEach
    void setUp() {
        service = new MetaTagService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static MetaTag tag(long id, String key, String content) {
        MetaTag t = new MetaTag();
        t.setId(id);
        t.setAttribute(NAME);
        t.setKey(key);
        t.setContent(content);
        t.setUpdatedAt(NOW);
        return t;
    }

    static ResponseStatusException status(Throwable t) {
        return (ResponseStatusException) t;
    }

    @Test
    void list_mapsEveryTagInTheRepositorysOrder() {
        when(repository.findAllByOrderByAttributeAscKeyAsc()).thenReturn(List.of(tag(1, "theme-color", "#000")));
        assertThat(service.list()).containsExactly(new MetaTagDto(1, NAME, "theme-color", "#000", NOW));
    }

    @Test
    void create_savesTheTag_stampedNow() {
        when(repository.findByAttributeAndKeyIgnoreCase(PROPERTY, "og:image")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> {
            MetaTag t = i.getArgument(0);
            t.setId(7L);
            return t;
        });
        assertThat(service.create(new MetaTagRequest(PROPERTY, "og:image", "https://x/y.png")))
                .isEqualTo(new MetaTagDto(7, PROPERTY, "og:image", "https://x/y.png", NOW));
    }

    @Test
    void create_refusesAKeyAlreadyConfigured_whateverItsCase() {
        when(repository.findByAttributeAndKeyIgnoreCase(NAME, "Google-Site-Verification")).thenReturn(Optional.of(tag(1, "google-site-verification", "a")));
        assertThatThrownBy(() -> service.create(new MetaTagRequest(NAME, "Google-Site-Verification", "b")))
                .satisfies(t -> assertThat(status(t).getStatusCode().value()).isEqualTo(409));
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1abc", "has space", "a\"b", "a<b", "description", "Robots", "og:title", "viewport"})
    void create_refusesAnInvalidOrReservedKey(String key) {
        assertThatThrownBy(() -> service.create(new MetaTagRequest(NAME, key, "x")))
                .satisfies(t -> assertThat(status(t).getStatusCode().value()).isEqualTo(400));
        verify(repository, never()).save(any());
    }

    @Test
    void create_refusesATooLongKey_aContentBlankOnSeveralLinesOrTooLong_andAMissingAttribute() {
        for (MetaTagRequest request : List.of(
                new MetaTagRequest(NAME, "a".repeat(101), "x"),
                new MetaTagRequest(NAME, "k", "a\nb"),
                new MetaTagRequest(NAME, "k", "a".repeat(1001)),
                new MetaTagRequest(NAME, "k", null),
                new MetaTagRequest(NAME, "k", ""),
                new MetaTagRequest(NAME, "k", "   "),
                new MetaTagRequest(NAME, "k", "a\u2028b"),
                new MetaTagRequest(null, "k", "x"))) {
            assertThatThrownBy(() -> service.create(request))
                    .satisfies(t -> assertThat(status(t).getStatusCode().value()).isEqualTo(400));
        }
        verify(repository, never()).save(any());
    }

    @Test
    void update_replacesTheTag_butNotOntoAnotherOnesKey() {
        MetaTag mine = tag(1, "theme-color", "#000");
        when(repository.findById(1L)).thenReturn(Optional.of(mine));
        when(repository.findByAttributeAndKeyIgnoreCase(NAME, "theme-color")).thenReturn(Optional.of(mine));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.update(1, new MetaTagRequest(NAME, "theme-color", "#fff")).content()).isEqualTo("#fff");

        when(repository.findByAttributeAndKeyIgnoreCase(NAME, "other")).thenReturn(Optional.of(tag(2, "other", "x")));
        assertThatThrownBy(() -> service.update(1, new MetaTagRequest(NAME, "other", "y")))
                .satisfies(t -> assertThat(status(t).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void updateAndDelete_answer404ForAnUnknownTag() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(9, new MetaTagRequest(NAME, "k", "x")))
                .satisfies(t -> assertThat(status(t).getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.delete(9))
                .satisfies(t -> assertThat(status(t).getStatusCode().value()).isEqualTo(404));
    }
}
