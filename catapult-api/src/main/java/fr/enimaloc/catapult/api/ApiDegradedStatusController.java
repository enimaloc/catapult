package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.service.status.DegradedStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/status/degraded")
@RequiredArgsConstructor
public class ApiDegradedStatusController {

    private final DegradedStatusService degradedStatusService;

    @GetMapping
    public DegradedStatusDto status() {
        return degradedStatusService.status();
    }
}
