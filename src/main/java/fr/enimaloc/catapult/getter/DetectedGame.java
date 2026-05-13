package fr.esportline.catapult.getter;

import fr.esportline.catapult.domain.GameBinding;
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
