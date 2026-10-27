package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import lombok.Value;

/**
 * A game detected by a game getter.
 */
@Value
public class DetectedGame {
    String sourceId;
    GameBinding.SourceType sourceType;
    String sourceName;
}
