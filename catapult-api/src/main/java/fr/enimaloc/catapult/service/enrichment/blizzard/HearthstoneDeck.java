package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.util.List;

/** A Hearthstone deck decoded from its code. */
public record HearthstoneDeck(String code, String format, String heroName, String className, int cardCount,
                              List<Card> cards) {

    public HearthstoneDeck {
        cards = List.copyOf(cards);
    }

    public record Card(long id, String name, Integer manaCost, String image) {
    }
}
