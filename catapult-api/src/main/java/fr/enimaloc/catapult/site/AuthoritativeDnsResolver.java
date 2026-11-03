package fr.enimaloc.catapult.site;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

/** Asks every authoritative name server of the zone directly, so no resolver cache can hide a switch. */
@Slf4j
@Component
public class AuthoritativeDnsResolver implements DnsTargetResolver {

    @Override
    public Set<String> resolveA(String name) throws IOException {
        try {
            List<String> servers = nameServers(zoneOf(name));
            Set<String> addresses = new HashSet<>();
            boolean answered = false;
            for (String server : servers) {
                try {
                    List<String> answer = query(name, "A", "dns://" + server);
                    if (answer.isEmpty()) {
                        // No record is no answer: the name never stops pointing somewhere on purpose
                        log.debug("Authoritative server {} returned no A record for {}", server, name);
                        continue;
                    }
                    addresses.addAll(answer);
                    answered = true;
                } catch (NamingException e) {
                    // another authoritative server may still answer
                    log.debug("Authoritative server {} failed for {}: {}", server, name, e.getMessage());
                }
            }
            if (!answered) {
                throw new IOException("No authoritative server returned an A record for " + name);
            }
            return addresses;
        } catch (NamingException e) {
            throw new IOException("DNS lookup failed for " + name, e);
        }
    }

    private static String zoneOf(String name) {
        String[] labels = name.split("\\.");
        if (labels.length <= 2) {
            return name;
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private List<String> nameServers(String zone) throws NamingException {
        List<String> servers = new ArrayList<>(query(zone, "NS", "dns:"));
        servers.replaceAll(s -> s.endsWith(".") ? s.substring(0, s.length() - 1) : s);
        return servers;
    }

    private List<String> query(String name, String type, String providerUrl) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", providerUrl);
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext ctx = new InitialDirContext(env);
        try {
            Attributes attrs = ctx.getAttributes(name, new String[]{type});
            Attribute attr = attrs.get(type);
            List<String> values = new ArrayList<>();
            if (attr != null) {
                NamingEnumeration<?> all = attr.getAll();
                while (all.hasMore()) {
                    values.add(String.valueOf(all.next()));
                }
            }
            return values;
        } finally {
            ctx.close();
        }
    }
}
