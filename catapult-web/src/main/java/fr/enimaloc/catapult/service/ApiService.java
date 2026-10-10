package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;

public interface ApiService {
    TokenResponse exchangeCode(String code);
    ChannelListResponse channelList();
    ChannelPageData channelPage(String username, int page, String status, String source);
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
    LinkStateResponse minecraftStatus(String username);
    void minecraftEnroll(String username, String name);
    void minecraftSync(String username);
    void minecraftDisconnect(String username);
    void saveCclSettings(String username, boolean enabled, java.util.Set<String> blockedCcls);
    void saveTwSettings(String username, boolean enabled, java.util.Set<String> blockedTws);
    UserSettingsDto channelSettings(String username);
    void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls,
                             boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd);
    void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls);
    DtddMappingStatusDto dtddMappingStatus(String username);
    SearchResponse dtddSearch(String q);
    void dtddValidate(String igdbId);
    void dtddPropose(String igdbId, Long dtddId, String reason);
}
