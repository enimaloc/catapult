package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.service.emergency.EmergencyViews;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The global emergency stops, public like the degraded status: the status banner reads them without a login. */
@RestController
@RequestMapping(ApiPaths.INTERNAL + "/emergency/status")
@RequiredArgsConstructor
public class EmergencyStatusController {

    private final EmergencyViews views;

    @GetMapping
    public EmergencyStatusDto status() {
        return views.status();
    }
}
