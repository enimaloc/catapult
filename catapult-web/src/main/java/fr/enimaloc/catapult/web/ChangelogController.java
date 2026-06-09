package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.dto.ChangelogDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/changelog")
@RequiredArgsConstructor
public class ChangelogController {

    private static final ParameterizedTypeReference<List<ChangelogDto.ChangelogSection>> SECTIONS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final ApiClient apiClient;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("sections", apiClient.get("/api/changelog", SECTIONS_TYPE));
        return "changelog";
    }

    @GetMapping("/fragment")
    public String fragment(Model model) {
        model.addAttribute("sections", apiClient.get("/api/changelog", SECTIONS_TYPE));
        return "fragments/changelog-modal :: content";
    }
}
