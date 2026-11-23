package fr.enimaloc.catapult.common.dto.channel;

/** The Riot ID a member declares as theirs ({@code gameName#tagLine}) and the League of Legends platform ({@code euw1}…). */
public record RiotAccountRequest(String riotId, String platform) {}
