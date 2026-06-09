package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.security.CatapultWebUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ChannelsController {

    private final ApiClient apiClient;

    @GetMapping("/channels")
    public String channels(@AuthenticationPrincipal CatapultWebUser user, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = apiClient.get("/api/channels", Map.class);
        if (response == null) {
            model.addAttribute("channels", List.of());
            model.addAttribute("liveStatus", Map.of());
            model.addAttribute("viewerTwitchId", user != null ? user.getTwitchId() : "");
            return "channels";
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channelList = (List<Map<String, Object>>) response.get("channels");
        if (channelList != null && channelList.size() == 1) {
            return "redirect:/channels/" + channelList.get(0).get("twitchUsername");
        }

        Map<String, Boolean> liveStatus = channelList == null ? Map.of() :
                channelList.stream().collect(
                        java.util.stream.Collectors.toMap(
                                c -> (String) c.get("id"),
                                c -> Boolean.TRUE.equals(c.get("live"))
                        )
                );

        model.addAttribute("channels", channelList != null ? channelList : List.of());
        model.addAttribute("liveStatus", liveStatus);
        model.addAttribute("viewerTwitchId", response.get("viewerTwitchId"));
        return "channels";
    }
}
