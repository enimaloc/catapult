package fr.enimaloc.catapult.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface SteamStoreService {
    Map<String, Set<String>> fetchCcls(Collection<String> appIds);
}
