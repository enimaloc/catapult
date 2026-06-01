package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.service.ChangelogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/changelog")
@RequiredArgsConstructor
public class ChangelogController {

    private final ChangelogService changelogService;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("sections", changelogService.getSections());
        return "changelog";
    }

    @GetMapping("/fragment")
    public String fragment(Model model) {
        model.addAttribute("sections", changelogService.getSections());
        return "fragments/changelog-modal :: content";
    }
}
