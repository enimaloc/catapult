package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.common.dto.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.TokenResponse;

public interface ApiService {
    TokenResponse exchangeCode(String code);
    ChannelListResponse channelList();
    fr.enimaloc.catapult.common.dto.ChannelPageData channelPage(String username, int page, String status, String source);
    void toggleBot(String username);
    void recheckGame(String username);
    void cclToggle(String username, String bindingId, boolean enabled);
    void ignoredToggle(String username, String bindingId, boolean ignored);
    void deleteBinding(String username, String bindingId);
    Object searchGames(String username, String q);
    void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls);
    void saveTws(String bindingId, java.util.Set<String> tws);
    void resetTws(String bindingId);
    void toggleTwEnabled(String bindingId, boolean enabled);
    void saveSteamToken(String username, String token, boolean shared);
    void steamTokenSharing(String username, boolean shared);
    void deleteSteamToken(String username);
    void refreshSteamProfileCache(String username);
}
