package fr.enimaloc.catapult.service.twitch;

import java.util.List;

public interface TwitchCategoryService {
    List<TwitchCategory> searchCategories(String query);
}
