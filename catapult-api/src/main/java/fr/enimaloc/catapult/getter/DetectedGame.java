package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import lombok.Value;

/**
 * Représente un jeu détecté par un game getter.
 */
@Value
public class DetectedGame {
    String sourceId;
    GameBinding.SourceType sourceType;
    String sourceName;
}
