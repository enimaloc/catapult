package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test d'intégration de la chaîne complète d'authentification Minecraft :
 * rpsTicket → user token Xbox → token XSTS → token Minecraft.
 * Skippé sauf si {@code XBOX_RPS_TICKET} est défini :
 *
 * <pre>
 * XBOX_RPS_TICKET="d=&lt;ticket&gt;" ./gradlew :catapult-api:test --tests XboxAndMinecraftServiceIT
 * </pre>
 * <p>
 * Le préfixe {@code d=} est requis pour un ticket issu du flux OAuth Live
 * (scope {@code XboxLive.signin}) ; il est ajouté automatiquement s'il manque.
 */
@EnabledIfEnvironmentVariable(named = "XBOX_RPS_TICKET", matches = ".+")
class XboxAndMinecraftServiceIT {

    @Test
    void integration() {
        var rpsTicket = System.getenv("XBOX_RPS_TICKET");
        if (!rpsTicket.startsWith("d=") && !rpsTicket.startsWith("t=")) {
            rpsTicket = "d=" + rpsTicket;
        }

        var xboxService = new XboxService(RestClient.create());
        var minecraftService = new MinecraftService(RestClient.create());

        var token = xboxService.getXboxToken(rpsTicket);

        assertThat(token).isNotNull();
        assertThat(token.token()).isNotBlank();
        assertThat(token.issueInstant()).isBeforeOrEqualTo(Instant.now());
        assertThat(token.notAfter()).isAfter(Instant.now());
        assertThat(token.displayClaims()).isNotNull();
        assertThat(token.displayClaims().xui()).isNotEmpty();
        assertThat(token.displayClaims().xui()[0].uhs()).isNotBlank();

        System.out.println("Xbox user token OK");
        System.out.println("  uhs      = " + token.displayClaims().xui()[0].uhs());
        System.out.println("  notAfter = " + token.notAfter());
        System.out.println("  token    = " + token.token().substring(0, Math.min(40, token.token().length())) + "…");

        // XSTS attend le user token de l'étape 1, pas le rpsTicket
        var xstsToken = xboxService.getXstsToken(token);

        assertThat(xstsToken).isNotNull();
        assertThat(xstsToken.token()).isNotBlank();
        assertThat(xstsToken.issueInstant()).isBeforeOrEqualTo(Instant.now());
        assertThat(xstsToken.notAfter()).isAfter(Instant.now());
        assertThat(xstsToken.displayClaims()).isNotNull();
        assertThat(xstsToken.displayClaims().xui()).isNotEmpty();
        assertThat(xstsToken.displayClaims().xui()[0].uhs()).isNotBlank();

        System.out.println("Xsts token OK");
        System.out.println("  uhs      = " + xstsToken.displayClaims().xui()[0].uhs());
        System.out.println("  notAfter = " + xstsToken.notAfter());
        System.out.println("  token    = " + xstsToken.token().substring(0, Math.min(40, xstsToken.token().length())) + "…");

        MinecraftService.Token minecraftToken = minecraftService.getMinecraftToken(xstsToken);

        assertThat(minecraftToken).isNotNull();
        assertThat(minecraftToken.accessToken()).isNotBlank();
        assertThat(minecraftToken.username()).isNotBlank();
        assertThat(minecraftToken.tokenType()).isEqualTo("Bearer");
        assertThat(minecraftToken.expiresIn()).isPositive();

        System.out.println("Minecraft token OK");
        System.out.println("  username    = " + minecraftToken.username());
        System.out.println("  tokenType   = " + minecraftToken.tokenType());
        System.out.println("  expiresIn   = " + minecraftToken.expiresIn() + "s");
        System.out.println("  accessToken = " + minecraftToken.accessToken().substring(0, Math.min(40, minecraftToken.accessToken().length())) + "…");

        MinecraftService.FriendsList friends = minecraftService.getFriends(minecraftToken);

        assertThat(friends).isNotNull();
        assertThat(friends.friends()).isNotNull();
        assertThat(friends.incomingRequests()).isNotNull();
        assertThat(friends.outgoingRequests()).isNotNull();

        System.out.println("Minecraft friends OK");
        System.out.println("  friends    = " + Arrays.toString(friends.friends()));
        System.out.println("  incoming   = " + Arrays.toString(friends.incomingRequests()));
        System.out.println("  outgoing   = " + Arrays.toString(friends.outgoingRequests()));
        System.out.println("  empty      = " + friends.empty());
        for (MinecraftService.FriendsList.Friend friend : friends.friends()) {
            System.out.printf("Removing friend: %s(%s)%n", friend.name(), friend.profileId());
            minecraftService.removeFriend(minecraftToken, friend);
        }

        var afterAdd = minecraftService.addFriend(minecraftToken, "enimaloc", null);

        assertThat(afterAdd).isNotNull();
        System.out.println("Minecraft addFriend OK");
        System.out.println("  friends    = " + Arrays.toString(afterAdd.friends()));
        System.out.println("  outgoing   = " + Arrays.toString(afterAdd.outgoingRequests()));

        System.out.println("En attente de l'acceptation de la demande d'ami par enimaloc…");
        await("acceptation de la demande d'ami par enimaloc")
                .atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(10))
                .until(() -> Arrays.stream(minecraftService.getFriends(minecraftToken).friends())
                        .anyMatch(friend -> "enimaloc".equalsIgnoreCase(friend.name())));
        System.out.println("Demande d'ami acceptée");

        var presences = minecraftService.updatePresence(minecraftToken, MinecraftService.PresenceStatus.ONLINE);

        assertThat(presences).isNotNull();
        assertThat(presences.presence()).isNotNull();

        System.out.println("Minecraft presence OK");
        for (MinecraftService.PresenceList.Presence presence : presences.presence()) {
            System.out.printf("  %s -> %s (lastUpdated=%s)%n",
                    presence.profileId(), presence.status(), presence.lastUpdated());
        }

        // Retour OFFLINE pour ne pas laisser une fausse présence sur le compte
        minecraftService.updatePresence(minecraftToken, MinecraftService.PresenceStatus.OFFLINE);
    }
}
