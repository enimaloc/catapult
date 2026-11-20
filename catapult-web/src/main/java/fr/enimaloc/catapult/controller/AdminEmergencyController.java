package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin emergency tab's actions, forwarded to catapult-api with the session's JWT: a provider ({@code ALL}: every
 * one) stopped instance-wide, or resumed. They answer catapult-api's status; 502 when it is unreachable.
 */
@RestController
@RequestMapping("/admin/emergency")
@RequiredArgsConstructor
public class AdminEmergencyController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/stop")
    public ResponseEntity<Void> stop(@RequestParam String provider) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminEmergencyStop(provider)).build();
    }

    @PostMapping("/resume")
    public ResponseEntity<Void> resume(@RequestParam String provider) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminEmergencyResume(provider)).build();
    }
}
