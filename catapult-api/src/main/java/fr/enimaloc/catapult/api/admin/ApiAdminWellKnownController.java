package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.service.wellknown.IanaWellKnownRegistry;
import fr.enimaloc.catapult.service.wellknown.SchemaStoreRegistry;
import fr.enimaloc.catapult.service.wellknown.WellKnownFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The admin page's well-known files (served publicly through {@link fr.enimaloc.catapult.api.ApiWellKnownController}). */
@RestController
@RequestMapping("/api/admin/well-known")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminWellKnownController {
    private final WellKnownFileService service;
    private final IanaWellKnownRegistry registry;
    private final SchemaStoreRegistry schemas;

    @GetMapping
    public List<WellKnownFileDto> list() {
        return service.list();
    }

    /** IANA's registry, live or the bundled copy (see {@link IanaWellKnownRegistry}). */
    @GetMapping("/iana")
    public IanaRegistryDto iana() {
        return registry.registry();
    }

    /** SchemaStore's JSON Schema for {@code path}, for the admin's generated form; 404 when there is none. */
    @GetMapping(value = "/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schema(@RequestParam String path) {
        return schemas.schemaFor(path).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public WellKnownFileDetailDto get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WellKnownFileDetailDto create(@RequestBody WellKnownFileRequest body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public WellKnownFileDetailDto update(@PathVariable long id, @RequestBody WellKnownFileRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
