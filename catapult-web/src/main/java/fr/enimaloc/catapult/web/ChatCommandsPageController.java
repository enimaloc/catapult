package fr.enimaloc.catapult.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class ChatCommandsPageController {

    @GetMapping("/chat-commands")
    public String page() {
        return "chat-commands";
    }
}
