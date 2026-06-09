package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;
import java.util.Optional;

public interface TwitchService {
    void updateChannel(UserAccount user, GameBinding binding);
    Optional<String> findCategoryIdByName(UserAccount user, String gameName);
    List<TwitchCategory> searchCategories(UserAccount user, String query);
    void resetToDefault(UserAccount user);
    List<String> getModeratedChannelIds(UserAccount viewer);
}
