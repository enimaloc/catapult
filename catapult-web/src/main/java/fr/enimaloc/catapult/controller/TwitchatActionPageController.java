package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Target of the Catapult buttons in Twitchat's chat. Twitchat's "url" button fetch()es it from
 * https://twitchat.fr's page (Origin twitchat.fr, Sec-Fetch-Mode: cors): without this CORS
 * allowance the browser blocks the answer and the action looks failed, though it ran.
 */
@Controller
@RequiredArgsConstructor
public class TwitchatActionPageController {

    private final ApiService apiService;

    @CrossOrigin(origins = "https://twitchat.fr")
    @GetMapping("/widget/twitchat/action/{token}")
    public String action(@PathVariable String token, Model model) {
        model.addAttribute("executed", apiService.executeTwitchatAction(token));
        return "pages/twitchat-action";
    }
}
