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
public class WidgetTwitchatController {

    private final ApiClient apiClient;

    @GetMapping("/widget/twitchat/{token}")
    public String widgetPage(@PathVariable String token, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = apiClient.get("/api/twitchat/widget/{token}", Map.class, token);
        model.addAttribute("widgetToken", token);
        model.addAttribute("obsHost", config == null ? null : config.get("obsHost"));
        model.addAttribute("obsPort", config == null ? null : config.get("obsPort"));
        model.addAttribute("obsPassword", config == null ? "" : config.getOrDefault("obsPassword", ""));
        return "widget/twitchat";
    }

    @GetMapping("/widget/twitchat/action/{token}")
    public String actionPage(@PathVariable String token, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = apiClient.post("/api/twitchat/actions/{token}", null, Map.class, token);
        model.addAttribute("executed", result != null && "EXECUTED".equals(result.get("result")));
        return "widget/twitchat-action";
    }
}
