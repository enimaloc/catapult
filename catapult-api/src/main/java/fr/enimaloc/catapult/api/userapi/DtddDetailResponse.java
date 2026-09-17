package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.getter.DtddApiClient;

import java.util.List;

public record DtddDetailResponse(String url, List<String> yesTopics, List<String> noTopics,
                                 List<String> mostlyTopics, String overview, String[] genres,
                                 long releaseYear, String itemTypeName, long tmdbId, String imdbId,
                                 String posterImage, String backgroundImage,
                                 DtddApiClient.DtddTopicItemStat[] topicItemStats) {}
