package fr.enimaloc.catapult.service.enrichment.pcgw;

/**
 * PC port details of a game, as PCGamingWiki states them. The support fields are the raw wiki values
 * ({@code true}, {@code false}, {@code limited}, {@code hackable}, {@code unknown}...), null when the wiki has none.
 *
 * @param pageId MediaWiki page id
 * @param title  wiki page name
 * @param url    {@code https://www.pcgamingwiki.com/wiki/{encoded title}}
 */
public record PcGamingWikiPage(long pageId, String title, String url, String ultrawide, String controller,
                               String hdr) {
}
