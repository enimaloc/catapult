package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.admindata.AdminDataService;
import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/data")
@RequiredArgsConstructor
public class ApiAdminDataController {

    private final AdminDataService data;

    @GetMapping
    public List<RepoSummaryDto> listRepositories() {
        return data.repositories();
    }

    @GetMapping("/{repo}")
    public Page<Map<String, Object>> entityList(
            @PathVariable String repo,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return data.list(repo, q, page, size);
    }

    @GetMapping("/{repo}/{id}")
    public Map<String, Object> entityDetail(@PathVariable String repo, @PathVariable String id) {
        return data.detail(repo, id);
    }

    @PostMapping("/{repo}")
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable String repo, @RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(data.create(repo, body));
    }

    @PutMapping("/{repo}/{id}")
    public Map<String, Object> update(@PathVariable String repo, @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return data.update(repo, id, body);
    }

    @DeleteMapping("/{repo}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String repo, @PathVariable String id) {
        data.delete(repo, id);
        return ResponseEntity.noContent().build();
    }
}
