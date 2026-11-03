package fr.enimaloc.catapult.site;

import java.io.IOException;
import java.util.Set;

public interface DnsTargetResolver {

    /** The union of the A records each authoritative server returns; throws when none answers. */
    Set<String> resolveA(String name) throws IOException;
}
