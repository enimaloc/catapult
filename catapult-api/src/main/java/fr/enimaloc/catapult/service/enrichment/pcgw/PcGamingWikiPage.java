package fr.enimaloc.catapult.service.enrichment.pcgw;

import java.util.List;

/**
 * PC port details of a game, as PCGamingWiki states them. The support fields are the raw wiki values
 * ({@code true}, {@code false}, {@code limited}, {@code hackable}, {@code unknown}...), null when the wiki has none.
 *
 * @param pageId        MediaWiki page id
 * @param title         wiki page name
 * @param url           {@code https://www.pcgamingwiki.com/wiki/{encoded title}}
 * @param saveLocations save-game locations; empty for now (they live in the page wikitext, not in Cargo)
 */
public record PcGamingWikiPage(long pageId, String title, String url, String ultrawide, String controller,
                               String hdr, List<String> saveLocations) {
}
