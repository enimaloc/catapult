package fr.enimaloc.catapult.service.igdb;

import java.util.Collection;
import java.util.Map;

/**
 * The Epic catalog as {@link IgdbService} needs it to confirm an IGDB game: the price tier of Epic Store offers,
 * which starts with the offer's product id. Provided by the Epic module, absent when Epic is disabled.
 */
public interface EpicCatalogClient {

    /** Maps each known offer id to its price tier; empty when nothing can be checked (no open session, failure). */
    Map<String, String> priceTiers(Collection<String> offerIds);
}
