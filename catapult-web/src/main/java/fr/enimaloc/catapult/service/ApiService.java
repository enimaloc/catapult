package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.common.dto.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.TokenResponse;

public interface ApiService {
    TokenResponse exchangeCode(String code);
    ChannelListResponse channelList();
    fr.enimaloc.catapult.common.dto.ChannelPageData channelPage(String username, int page, String status, String source);
    void toggleBot(String username);
    void recheckGame(String username);
}
