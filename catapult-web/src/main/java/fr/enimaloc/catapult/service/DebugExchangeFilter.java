package fr.enimaloc.catapult.service;

import java.time.Instant;

/** What the admin narrows a debugged member's exchanges to; every field is optional (null = no filter). */
public record DebugExchangeFilter(String service, String direction, String outcome, Instant from, Instant to) {
}
