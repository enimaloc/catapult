package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StatusController {

    private final ApiHealthService apiHealthService;

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("available", apiHealthService.isAvailable());
    }
}
