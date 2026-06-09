package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.service.ChangelogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/changelog")
@RequiredArgsConstructor
public class ApiChangelogController {

    private final ChangelogService changelogService;

    @GetMapping
    public List<ChangelogService.ChangelogSection> changelog() {
        return changelogService.getSections();
    }
}
