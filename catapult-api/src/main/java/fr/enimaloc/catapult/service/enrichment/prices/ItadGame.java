package fr.enimaloc.catapult.service.enrichment.prices;

/** A game as IsThereAnyDeal knows it: its id (uuid), the slug of its page and its title. */
public record ItadGame(String id, String slug, String title) {
}
