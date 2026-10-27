package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.common.dto.admin.WellKnownHeader;
import fr.enimaloc.catapult.common.dto.admin.WellKnownResponseDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.domain.wellknown.WellKnownFile;
import fr.enimaloc.catapult.repository.wellknown.WellKnownFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The well-known files the admin configures and catapult-web serves (see {@link WellKnownPaths}).
 * A path is configured once: a second one is a 409, also when two admins add it at once (the
 * table's unique constraint), and an update of a file deleted meanwhile is a 404.
 */
@Service
public class WellKnownFileService {
    private final WellKnownFileRepository repository;
    private final Clock clock;

    @Autowired
    public WellKnownFileService(WellKnownFileRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WellKnownFileService(WellKnownFileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<WellKnownFileDto> list() {
        return repository.findAllByOrderByLocationAscPathAsc().stream().map(WellKnownFileService::summary).toList();
    }

    public WellKnownFileDetailDto get(long id) {
        return detail(existing(id));
    }

    public WellKnownFileDetailDto create(WellKnownFileRequest request) {
        Normalized normalized = validate(request);
        if (repository.findByLocationAndPath(request.location(), request.path()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Path already configured");
        }
        return save(fill(new WellKnownFile(), request, normalized));
    }

    public WellKnownFileDetailDto update(long id, WellKnownFileRequest request) {
        WellKnownFile file = existing(id);
        Normalized normalized = validate(request);
        repository.findByLocationAndPath(request.location(), request.path())
                .filter(other -> !other.getId().equals(file.getId()))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Path already configured");
                });
        return save(fill(file, request, normalized));
    }

    public void delete(long id) {
        repository.delete(existing(id));
    }

    /** The file served at {@code path}; empty when there is none or the path breaks the rules. */
    public Optional<WellKnownFile> find(WellKnownLocation location, String path) {
        if (!WellKnownPaths.valid(location, path)) return Optional.empty();
        return repository.findByLocationAndPath(location, path);
    }

    private WellKnownFileDetailDto save(WellKnownFile file) {
        try {
            return detail(repository.save(file));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Path already configured");
        } catch (OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown well-known file");
        }
    }

    private WellKnownFile existing(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown well-known file"));
    }

    /** The status and headers of a request once the defaults for old clients (no status, no headers) are applied. */
    private record Normalized(int status, List<WellKnownHeader> headers) {}

    private static Normalized validate(WellKnownFileRequest request) {
        if (!WellKnownPaths.valid(request.location(), request.path())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        int status = request.status() == null || request.status() == 0 ? 200 : request.status();
        List<WellKnownHeader> headers = request.headers() == null ? List.of() : request.headers();
        WellKnownHeaders.validate(status, headers);
        if (request.content() == null
                || request.content().getBytes(StandardCharsets.UTF_8).length > WellKnownPaths.MAX_CONTENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content missing or too large");
        }
        String type = request.contentType();
        // A redirect has no body, so its type may be left blank (a default is stored); a body needs one.
        if (type == null || (type.isBlank() && !request.content().isEmpty()) || type.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid content type");
        }
        if (!type.isBlank()) {
            try {
                MediaType.parseMediaType(type);
            } catch (InvalidMediaTypeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid content type");
            }
        }
        return new Normalized(status, headers);
    }

    private WellKnownFile fill(WellKnownFile file, WellKnownFileRequest request, Normalized normalized) {
        file.setLocation(request.location());
        file.setPath(request.path());
        file.setStatus(normalized.status());
        file.setHeaders(WellKnownHeaders.toJson(normalized.headers()));
        file.setContentType(request.contentType().isBlank() ? "text/plain; charset=utf-8" : request.contentType().trim());
        file.setContent(request.content());
        file.setUpdatedAt(clock.instant());
        return file;
    }

    /** What catapult-web must answer for {@code f}. */
    public static WellKnownResponseDto response(WellKnownFile f) {
        return new WellKnownResponseDto(f.getStatus(), f.getContentType(), WellKnownHeaders.fromJson(f.getHeaders()),
                f.getContent());
    }

    private static WellKnownFileDto summary(WellKnownFile f) {
        return new WellKnownFileDto(f.getId(), f.getLocation(), f.getPath(), f.getStatus(),
                f.getContentType(), f.getContent().getBytes(StandardCharsets.UTF_8).length, f.getUpdatedAt());
    }

    private static WellKnownFileDetailDto detail(WellKnownFile f) {
        return new WellKnownFileDetailDto(f.getId(), f.getLocation(), f.getPath(), f.getStatus(),
                WellKnownHeaders.fromJson(f.getHeaders()), f.getContentType(), f.getContent(), f.getUpdatedAt());
    }
}
