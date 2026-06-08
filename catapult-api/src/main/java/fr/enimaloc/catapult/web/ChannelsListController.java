package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.StreamStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ChannelsListController {

    private final ChannelAccessService channelAccessService;
    private final StreamStateService streamStateService;

    @GetMapping("/channels")
    public String channels(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount viewer = principal.getUserAccount();
        List<UserAccount> accessible = channelAccessService.getAccessibleChannels(viewer);

        if (accessible.size() == 1) {
            return "redirect:/channels/" + accessible.get(0).getTwitchUsername();
        }

        Map<UUID, Boolean> liveStatus = accessible.stream()
            .collect(Collectors.toMap(UserAccount::getId, streamStateService::isLive));

        model.addAttribute("channels", accessible);
        model.addAttribute("liveStatus", liveStatus);
        model.addAttribute("viewerTwitchId", viewer.getTwitchId());
        return "channels";
    }
}
