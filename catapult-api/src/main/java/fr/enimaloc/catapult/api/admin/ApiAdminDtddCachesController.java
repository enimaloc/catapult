package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.DtddCachesSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.service.dtdd.DtddCacheAdminService;
import fr.enimaloc.catapult.service.dtdd.DtddCacheKind;
import fr.enimaloc.catapult.service.dtdd.DtddCacheRefetchJob;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The DTDD caches (a title's topics, the titles, the searches): their summary and the keys' quotas, each one paged,
 * an entry fetched again, and the bulk refetch of them all ({@code /refetch-all}: started, followed, cancelled).
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/dtdd-caches")
@RequiredArgsConstructor
public class ApiAdminDtddCachesController {

    private final DtddCacheAdminService caches;
    private final DtddCacheRefetchJob job;

    @GetMapping
    public DtddCachesSummaryDto summary() {
        return caches.summary();
    }

    @GetMapping("/refetch-all")
    public DtddRefetchJobDto job() {
        return job.status();
    }

    @PostMapping("/refetch-all")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DtddRefetchJobDto startJob() {
        return job.start();
    }

    @DeleteMapping("/refetch-all")
    public DtddRefetchJobDto cancelJob() {
        return job.cancel();
    }

    @GetMapping("/{kind}")
    public Page<?> list(@PathVariable String kind, @RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return caches.list(DtddCacheKind.of(kind), q, page, size);
    }

    /** {@code key}: the title's ID, or the search's query. */
    @PostMapping("/{kind}/refetch")
    public Object refetch(@PathVariable String kind, @RequestParam String key) {
        return caches.refetch(DtddCacheKind.of(kind), key);
    }
}
