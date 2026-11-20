package fr.enimaloc.catapult.bgs.proto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTableTest {

    private static final Map<String, Integer> EXPECTED_HASHES = new LinkedHashMap<>();

    static {
        EXPECTED_HASHES.put("bgs.protocol.connection.v1.ConnectionService", 0x65446991);
        EXPECTED_HASHES.put("bgs.protocol.authentication.v2.client.AuthenticationService", 0xC02F8216);
        EXPECTED_HASHES.put("bgs.protocol.authentication.v2.client.AuthenticationListener", 0x9DA8116B);
        EXPECTED_HASHES.put("bgs.protocol.account.v1.AccountService", 0x62DA0891);
        EXPECTED_HASHES.put("bgs.protocol.account.v1.AccountListener", 0x54DFDA17);
        EXPECTED_HASHES.put("bgs.protocol.friends.v2.client.FriendsService", 0x5869BE8C);
        EXPECTED_HASHES.put("bgs.protocol.friends.v2.client.FriendsListener", 0xDBC01A89);
        EXPECTED_HASHES.put("bgs.protocol.presence.v2.client.PresenceService", 0x138D200C);
        EXPECTED_HASHES.put("bgs.protocol.presence.v2.PresenceListener", 0xF4729709);
    }

    @Test
    void hashesComputedFromDescriptorsMatchTheKnownTable() {
        Map<String, BgsCatalog.ServiceInfo> services = services();

        EXPECTED_HASHES.forEach((name, hash) ->
                assertThat(services.get(name)).as(name).isNotNull().extracting(BgsCatalog.ServiceInfo::hash).isEqualTo(hash));
        assertThat(BgsServices.FRIENDS_ACCEPT_INVITATION.serviceHash()).isEqualTo(0x5869BE8C);
        assertThat(BgsServices.FRIENDS_ACCEPT_INVITATION.methodId()).isEqualTo(9);
    }

    @Test
    void everyConstantMatchesItsDescriptorMethod() throws Exception {
        Map<String, BgsCatalog.ServiceInfo> services = services();
        List<ServiceMethod<?, ?>> methods = constants();
        assertThat(methods).hasSize(33);

        for (ServiceMethod<?, ?> m : methods) {
            BgsCatalog.ServiceInfo service = services.get("bgs.protocol." + m.service());
            assertThat(service).as(m.service()).isNotNull();
            BgsCatalog.MethodInfo method = service.methods().stream()
                    .filter(x -> x.id() == m.methodId()).findFirst().orElseThrow();
            String label = m.service() + "." + method.name();

            assertThat(m.serviceHash()).as(label).isEqualTo(service.hash());
            assertThat(m.requestParser().parsePartialFrom(new byte[0]).getDescriptorForType().getFullName())
                    .as(label + " request").isEqualTo(method.input().getFullName());
            if (method.noResponse()) {
                assertThat(m.responseParser()).as(label + " response").isNull();
            } else {
                assertThat(m.responseParser().parsePartialFrom(new byte[0]).getDescriptorForType().getFullName())
                        .as(label + " response").isEqualTo(method.output().getFullName());
            }
        }
    }

    @Test
    void rawHashesOfServicesAbsentFromTheDescriptors() {
        assertThat(BgsServices.SELECT_GAME_ACCOUNT_HASH).isEqualTo(0x7B37D770);
        assertThat(BgsServices.SELF_PRESENCE_HASH).isEqualTo(0xBCA72135);
        assertThat(BgsCatalog.instance().services()).extracting(BgsCatalog.ServiceInfo::hash)
                .doesNotContain(BgsServices.SELECT_GAME_ACCOUNT_HASH, BgsServices.SELF_PRESENCE_HASH);
    }

    @Test
    void fnv1aMatchesTheReferenceVectors() {
        assertThat(Fnv1a.hash("")).isEqualTo(0x811C9DC5);
        assertThat(Fnv1a.hash("a")).isEqualTo(0xE40C292C);
        assertThat(Fnv1a.hash("foobar")).isEqualTo(0xBF9CF968);
    }

    private static Map<String, BgsCatalog.ServiceInfo> services() {
        Map<String, BgsCatalog.ServiceInfo> services = new LinkedHashMap<>();
        BgsCatalog.instance().services().forEach(s -> services.put(s.fullName(), s));
        return services;
    }

    private static List<ServiceMethod<?, ?>> constants() throws IllegalAccessException {
        List<ServiceMethod<?, ?>> result = new ArrayList<>();
        for (Field f : BgsServices.class.getDeclaredFields()) {
            if (f.accessFlags().contains(AccessFlag.STATIC) && ServiceMethod.class.isAssignableFrom(f.getType())) {
                result.add((ServiceMethod<?, ?>) f.get(null));
            }
        }
        return result;
    }
}
