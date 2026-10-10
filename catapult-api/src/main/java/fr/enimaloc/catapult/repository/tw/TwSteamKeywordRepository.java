package fr.enimaloc.catapult.repository.tw;

import fr.enimaloc.catapult.domain.tw.TwSteamKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TwSteamKeywordRepository extends JpaRepository<TwSteamKeyword, Long> {
    List<TwSteamKeyword> findAllByTwId(String twId);
    void deleteAllByTwId(String twId);
}
