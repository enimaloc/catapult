package fr.enimaloc.catapult.bgs.proto;

import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.UpdateRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.TextFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BgsCatalogTest {

    private static final String PRESENCE = "bgs.protocol.presence.v2.client.PresenceService";

    @Test
    void findByName_presenceUpdate() {
        BgsCatalog.MethodInfo m = BgsCatalog.instance().find(PRESENCE, "Update").orElseThrow();

        assertThat(m.serviceHash()).isEqualTo(0x138D200C);
        assertThat(m.id()).isEqualTo(6);
        assertThat(m.input().getFullName()).isEqualTo("bgs.protocol.presence.v2.client.UpdateRequest");
        assertThat(m.noResponse()).isFalse();
    }

    @Test
    void findByHash_matchesFindByName() {
        assertThat(BgsCatalog.instance().find(0x138D200C, 6)).isEqualTo(BgsCatalog.instance().find(PRESENCE, "Update"));
    }

    @Test
    void findByHash_unknownService_isEmpty() {
        assertThat(BgsCatalog.instance().find(BgsServices.SELF_PRESENCE_HASH, 1)).isEmpty();
    }

    @Test
    void keepAlive_isNoResponse() {
        assertThat(BgsCatalog.instance().find(0x65446991, 5).orElseThrow().noResponse()).isTrue();
    }

    @Test
    void servicesAreSortedByName_withTheirMethodsSortedById() {
        var services = BgsCatalog.instance().services();

        assertThat(services).extracting(BgsCatalog.ServiceInfo::fullName).isSorted().contains(PRESENCE);
        assertThat(services.stream().filter(s -> s.fullName().equals(PRESENCE)).findFirst().orElseThrow().methods())
                .extracting(BgsCatalog.MethodInfo::id).isSorted();
    }

    @Test
    void encodeDecode_roundTrip() throws Exception {
        Descriptor in = BgsCatalog.instance().find(PRESENCE, "Update").orElseThrow().input();

        ByteString bytes = BgsCatalog.encode(in, "updates { key { group: 1 field: 12 } value { bool_value: true } }");

        UpdateRequest parsed = UpdateRequest.parseFrom(bytes);
        assertThat(parsed.getUpdates(0).getKey().getField()).isEqualTo(12);
        assertThat(parsed.getUpdates(0).getValue().getBoolValue()).isTrue();
        assertThat(BgsCatalog.decode(in, bytes)).get().asString().contains("field: 12");
    }

    @Test
    void decode_emptyBody_isEmptyText() {
        Descriptor out = BgsCatalog.instance().find(PRESENCE, "Update").orElseThrow().output();

        assertThat(BgsCatalog.decode(out, ByteString.EMPTY)).contains("");
    }

    @Test
    void encode_invalidText_throwsWithPosition() {
        Descriptor in = BgsCatalog.instance().find(PRESENCE, "Update").orElseThrow().input();

        assertThatThrownBy(() -> BgsCatalog.encode(in, "updates { nope: 1 }"))
                .isInstanceOf(TextFormat.ParseException.class).hasMessageContaining("1:");
    }

    @Test
    void decode_garbage_isEmpty() {
        Descriptor out = BgsCatalog.instance().find(0x138D200C, 5).orElseThrow().output();

        assertThat(BgsCatalog.decode(out, ByteString.copyFrom(new byte[]{(byte) 0xFF, 0x01}))).isEmpty();
    }
}
