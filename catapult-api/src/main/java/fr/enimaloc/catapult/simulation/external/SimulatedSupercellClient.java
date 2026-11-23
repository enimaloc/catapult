package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.supercell.SupercellClient;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Brawler;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.BsPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Card;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CocPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.CrPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Group;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.Hero;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellModels.SupercellPlayer;
import fr.enimaloc.catapult.service.enrichment.supercell.SupercellNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Supercell in the mock profile: every tag is a player of every game (named after the tag) except {@code #000},
 * which no game knows. The Clash of Clans player has town hall 16 and 5 200 trophies, the Clash Royale one 7 400
 * trophies in Legendary Arena, the Brawl Stars one 31 000 trophies and 2 400 3v3 victories.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedSupercellClient implements SupercellClient {

    static final String UNKNOWN = "#000";

    @Override
    public SupercellPlayer player(SupercellGame game, String tag) {
        if (UNKNOWN.equals(tag)) {
            throw new SupercellNotFoundException("simulated: no such player");
        }
        String name = "Chief " + tag.substring(1);
        return switch (game) {
            case CLASH_OF_CLANS -> new SupercellPlayer(tag, name, new CocPlayer(16, 3, 240, 5200, 5600, 1800, 412,
                    37, 10, 3900, "Legend League", null, new Group("#2PP", "Catapult"),
                    List.of(new Hero("Barbarian King", 90, 95, "home"), new Hero("Archer Queen", 92, 95, "home"))),
                    null, null);
            case CLASH_ROYALE -> new SupercellPlayer(tag, name, null, new CrPlayer(60, 7400, 7600, 3100, 2200, 900,
                    5600, "Legendary Arena", 4, new Group("#2PP", "Catapult"),
                    List.of(new Card("Hog Rider", 14, 15), new Card("Musketeer", 13, 15))), null);
            case BRAWL_STARS -> new SupercellPlayer(tag, name, null, null, new BsPlayer(180, 31000, 31500, 2400, 800,
                    300, new Group("#2PP", "Catapult"), List.of(new Brawler("SHELLY", 11, 30, 900, 1000))));
        };
    }
}
