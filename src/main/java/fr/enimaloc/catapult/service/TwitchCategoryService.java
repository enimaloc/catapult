package fr.enimaloc.catapult.service;

import java.util.List;

public interface TwitchCategoryService {
    List<TwitchCategory> searchCategories(String query);
}
