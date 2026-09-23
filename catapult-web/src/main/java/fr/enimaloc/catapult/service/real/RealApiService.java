package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Profile("!mock")
public class RealApiService implements ApiService {
    private final ApiClient client;

    @Override
    public TokenResponse exchangeCode(String code) {
        return client.post("/api/auth/exchange?code={code}", TokenResponse.class, code);
    }

    @Override
    public ChannelListResponse channelList() {
        return client.get("/api/channel", ChannelListResponse.class);
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        StringBuilder path = new StringBuilder("/api/channels/{username}?page={page}");
        List<Object> vars = new ArrayList<>(List.of(username, page));
        if (status != null && !status.isBlank()) {
            path.append("&status={status}");
            vars.add(status);
        } else if (source != null && !source.isBlank()) {
            path.append("&source={source}");
            vars.add(source);
        }
        return client.get(path.toString(), ChannelPageData.class, vars.toArray());
    }

    @Override
    public void toggleBot(String username) {
        client.postVoid("/api/channels/{username}/settings/bot", null, username);
    }

    @Override
    public void recheckGame(String username) {
        client.postVoid("/api/channels/{username}/game/recheck", null, username);
    }
}
