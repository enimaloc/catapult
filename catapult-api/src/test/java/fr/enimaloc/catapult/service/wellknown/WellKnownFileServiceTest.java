package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.domain.wellknown.WellKnownFile;
import fr.enimaloc.catapult.repository.wellknown.WellKnownFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WellKnownFileServiceTest {
    static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    @Mock WellKnownFileRepository repository;
    WellKnownFileService service;

    @BeforeEach
    void setUp() {
        service = new WellKnownFileService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static WellKnownFile file(long id, String path, String content) {
        WellKnownFile f = new WellKnownFile();
        f.setId(id);
        f.setLocation(WELL_KNOWN);
        f.setPath(path);
        f.setContentType("text/plain; charset=utf-8");
        f.setContent(content);
        f.setUpdatedAt(NOW);
        return f;
    }

    static int status(Throwable t) {
        return ((ResponseStatusException) t).getStatusCode().value();
    }

    @Test
    void create_savesAValidFile() {
        when(repository.findByLocationAndPath(WELL_KNOWN, "security.txt")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> { WellKnownFile f = i.getArgument(0); f.setId(7L); return f; });

        var created = service.create(new WellKnownFileRequest(WELL_KNOWN, "security.txt", "text/plain", "Contact: x"));

        assertThat(created.id()).isEqualTo(7L);
        assertThat(created.content()).isEqualTo("Contact: x");
        assertThat(created.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void create_refusesBadPathTypeOrSize_with400() {
        assertThatThrownBy(() -> service.create(new WellKnownFileRequest(ROOT, "riot", "text/plain", "x")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(400));
        assertThatThrownBy(() -> service.create(new WellKnownFileRequest(WELL_KNOWN, "a.txt", "not a type", "x")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(400));
        assertThatThrownBy(() -> service.create(new WellKnownFileRequest(WELL_KNOWN, "a.txt", " ", "x")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(400));
        assertThatThrownBy(() -> service.create(new WellKnownFileRequest(WELL_KNOWN, "a.txt", "text/plain", null)))
                .satisfies(t -> assertThat(status(t)).isEqualTo(400));
        // 32 769 two-byte characters: under 65 536 chars, over 65 536 UTF-8 bytes.
        assertThatThrownBy(() -> service.create(new WellKnownFileRequest(WELL_KNOWN, "a.txt", "text/plain", "é".repeat(32_769))))
                .satisfies(t -> assertThat(status(t)).isEqualTo(400));
        verify(repository, never()).save(any());
    }

    @Test
    void create_refusesATakenPath_with409() {
        when(repository.findByLocationAndPath(WELL_KNOWN, "security.txt")).thenReturn(Optional.of(file(1, "security.txt", "")));
        assertThatThrownBy(() -> service.create(new WellKnownFileRequest(WELL_KNOWN, "security.txt", "text/plain", "x")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(409));
    }

    @Test
    void update_keepsItsOwnPath_butRefusesAnotherFilesPath() {
        WellKnownFile own = file(1, "security.txt", "old");
        when(repository.findById(1L)).thenReturn(Optional.of(own));
        when(repository.findByLocationAndPath(WELL_KNOWN, "security.txt")).thenReturn(Optional.of(own));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.update(1, new WellKnownFileRequest(WELL_KNOWN, "security.txt", "text/plain", "new")).content())
                .isEqualTo("new");

        when(repository.findByLocationAndPath(WELL_KNOWN, "other.txt")).thenReturn(Optional.of(file(2, "other.txt", "")));
        assertThatThrownBy(() -> service.update(1, new WellKnownFileRequest(WELL_KNOWN, "other.txt", "text/plain", "x")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(409));
    }

    @Test
    void getUpdateDelete_unknownId_404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(9)).satisfies(t -> assertThat(status(t)).isEqualTo(404));
        assertThatThrownBy(() -> service.update(9, new WellKnownFileRequest(WELL_KNOWN, "a.txt", "text/plain", "")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(404));
        assertThatThrownBy(() -> service.delete(9)).satisfies(t -> assertThat(status(t)).isEqualTo(404));
    }

    @Test
    void list_reportsUtf8Sizes() {
        when(repository.findAllByOrderByLocationAscPathAsc()).thenReturn(List.of(file(1, "a.txt", "é")));
        assertThat(service.list()).singleElement().satisfies(f -> assertThat(f.size()).isEqualTo(2));
    }

    @Test
    void find_neverQueriesABrokenPath() {
        assertThat(service.find(WELL_KNOWN, "../x")).isEmpty();
        verifyNoInteractions(repository);
    }
}
