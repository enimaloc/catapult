package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin DTDD caches tab's actions, forwarded to catapult-api with the session's JWT: an entry fetched again
 * ({@code key}: the title's ID or the search's query), and the bulk refetch started or cancelled. They answer
 * catapult-api's status (and the refetch, as JSON); 502 when it is unreachable.
 */
@RestController
@RequestMapping("/admin/dtdd-caches")
@RequiredArgsConstructor
public class AdminDtddCachesController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/{kind}/refetch")
    public ResponseEntity<Void> refetch(@PathVariable String kind, @RequestParam String key) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminRefetchDtddCache(kind, key)).build();
    }

    @PostMapping("/refetch-all/start")
    public ResponseEntity<DtddRefetchJobDto> start() {
        AdminAccess.require(filler);
        return answer(apiService.adminStartDtddRefetch());
    }

    @PostMapping("/refetch-all/cancel")
    public ResponseEntity<DtddRefetchJobDto> cancel() {
        AdminAccess.require(filler);
        return answer(apiService.adminCancelDtddRefetch());
    }

    private static <T> ResponseEntity<T> answer(ApiClient.Result<T> result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
