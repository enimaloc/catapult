package fr.enimaloc.catapult.bgs.client;

/** Mints a fresh Battle.net login ticket ({@code EU-...}). Blocking: never called on the session loop. */
@FunctionalInterface
public interface TicketProvider {

    String mint() throws TicketException;
}
