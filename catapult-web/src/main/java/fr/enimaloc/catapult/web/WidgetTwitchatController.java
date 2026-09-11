package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class WidgetTwitchatController {

    private final ApiClient apiClient;

    @GetMapping("/widget/twitchat/{uuid}")
    public String widgetPage(@PathVariable String uuid, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = apiClient.get("/api/twitchat/widget/{uuid}", Map.class, uuid);
        model.addAttribute("widgetToken", uuid);
        model.addAttribute("obsHost", config == null ? null : config.get("obsHost"));
        model.addAttribute("obsPort", config == null ? null : config.get("obsPort"));
        model.addAttribute("obsPassword", config == null ? "" : config.getOrDefault("obsPassword", ""));
        return "widget/twitchat";
    }

    // Twitchat's "url" notification action doesn't navigate to this URL — it fetch()es it
    // in the background from https://twitchat.fr's own page context (confirmed from a HAR
    // capture: Origin: https://twitchat.fr, Sec-Fetch-Mode: cors, Sec-Fetch-Dest: empty).
    // Without an explicit CORS allowance the browser blocks the response and the action
    // silently fails from the streamer's point of view, even though it executed server-side.
    @CrossOrigin(origins = "https://twitchat.fr")
    @GetMapping("/widget/twitchat/action/{token}")
    public String actionPage(@PathVariable String token, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = apiClient.post("/api/twitchat/actions/{token}", null, Map.class, token);
        model.addAttribute("executed", result != null && "EXECUTED".equals(result.get("result")));
        return "widget/twitchat-action";
    }
}
