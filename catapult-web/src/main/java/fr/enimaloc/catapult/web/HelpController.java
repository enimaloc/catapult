package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class HelpController {

    private final ApiClient apiClient;

    @GetMapping("/help/{cardId}")
    public String helpPanel(@PathVariable String cardId, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, String> content = apiClient.get("/api/help/{cardId}", Map.class, cardId);
        if (content != null) {
            model.addAttribute("helpTitle", content.get("title"));
            model.addAttribute("helpBody", content.get("body"));
        } else {
            model.addAttribute("helpTitle", cardId);
            model.addAttribute("helpBody", "");
        }
        return "fragments/help-content :: help-content";
    }
}
