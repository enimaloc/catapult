package fr.enimaloc.catapult.service.epic.connect;

import fr.enimaloc.catapult.service.epic.connect.StompFrames.Frame;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StompFramesTest {

    private static final String BOT = "27871f9dd56446c8a1f1d3db1aaa8a7e";
    private static final String CONNECTION = "860311fffe81f8da-00000001-0559a369-77018bff483dc39d-0b90df0e";

    private static String messageFrame(String messageId, String body) {
        return "MESSAGE\ndestination:_/account/" + BOT + "\nsubscription:sub-0\nmessage-id:" + messageId
                + "\ncontent-type:application/json\ncontent-length:" + body.length() + "\n\n" + body + "\0";
    }

    @Test
    void connectFrame_isStomp12WithA30sHeartbeat() {
        assertThat(StompFrames.connect()).isEqualTo("CONNECT\naccept-version:1.2\nheart-beat:30000,0\n\n\0");
    }

    @Test
    void subscribe_targetsTheBotAccountDestination() {
        assertThat(StompFrames.subscribe(BOT))
                .isEqualTo("SUBSCRIBE\nid:sub-0\ndestination:_/account/" + BOT + "\n\n\0");
    }

    @Test
    void connectedFrame_fromTheSpike_isParsed() {
        Optional<Frame> frame = StompFrames.parse("CONNECTED\nversion:1.2\nheart-beat:0,45000\n"
                + "session:860311fffe81f8da-00000001-0559a369-77018bff483dc39d-0b90df0e\n\n\0");

        assertThat(frame).isPresent();
        assertThat(frame.get().command()).isEqualTo("CONNECTED");
        assertThat(frame.get().headers()).containsEntry("heart-beat", "0,45000").containsEntry("version", "1.2");
        assertThat(frame.get().body()).isEmpty();
    }

    @Test
    void heartbeat_andBlank_areEmpty() {
        assertThat(StompFrames.parse(StompFrames.HEARTBEAT)).isEmpty();
        assertThat(StompFrames.parse("")).isEmpty();
        assertThat(StompFrames.parse("\0")).isEmpty();
    }

    @Test
    void headerValues_areUnescapedPerStomp12() {
        Frame frame = StompFrames.parse("ERROR\nmessage:a\\cb\\nc\\\\d\n\n\0").orElseThrow();

        assertThat(frame.headers()).containsEntry("message", "a:b\nc\\d");
    }

    @Test
    void connectedMessage_givesTheConnectionId() {
        String body = "{\"correlationId\":\"bacccfa3-9b48-4e69-b7f3-1b2a99d3eb72\",\"timestamp\":1794722432007,"
                + "\"connectionId\":\"" + CONNECTION + "\",\"type\":\"core.connect.v1.connected\"}";

        EpicConnectMessage message = StompFrames.parse(messageFrame("13a158ac-0038-423c-8fa8-30f83014e66d", body))
                .flatMap(EpicConnectMessage::from).orElseThrow();

        assertThat(message.type()).isEqualTo("core.connect.v1.connected");
        assertThat(message.connectionId()).isEqualTo(CONNECTION);
    }

    @Test
    void presenceMessage_givesTheFallGuysPayload() {
        String body = "{\"correlationId\":\"EOS-MfaqFKUXRkiq3mF6GeyvsA-o3unRvud-kyBUmsq45pe6A\","
                + "\"timestamp\":1794722467217,\"id\":\"MmMwMDRjYWJkMmUxNDI4MG\",\"connectionId\":\"" + CONNECTION
                + "\",\"payload\":{\"accountId\":\"51752b8577e341a6a96f95f6a95f38d5\",\"status\":\"online\","
                + "\"perNs\":[{\"productId\":\"d42d7101bb7a41c09a810ee418b7d3d0\","
                + "\"appId\":\"fghi45677JW4O6a40cxsOpscCLcr3Z3j\",\"status\":\"online\"}]},"
                + "\"type\":\"presence.v1.UPDATE\"}";

        EpicConnectMessage message = StompFrames.parse(messageFrame("MmMwMDRjYWJkMmUxNDI4MG", body))
                .flatMap(EpicConnectMessage::from).orElseThrow();

        assertThat(message.type()).isEqualTo("presence.v1.UPDATE");
        assertThat(message.payload().path("accountId").asString()).isEqualTo("51752b8577e341a6a96f95f6a95f38d5");
        assertThat(message.payload().path("perNs").get(0).path("productId").asString())
                .isEqualTo("d42d7101bb7a41c09a810ee418b7d3d0");
    }

    @Test
    void nonMessageFrames_areNotMessages() {
        Frame error = StompFrames.parse("ERROR\nmessage:boom\n\n{\"type\":\"x\"}\0").orElseThrow();
        Frame receipt = StompFrames.parse("RECEIPT\nreceipt-id:1\n\n\0").orElseThrow();

        assertThat(EpicConnectMessage.from(error)).isEmpty();
        assertThat(EpicConnectMessage.from(receipt)).isEmpty();
    }

    @Test
    void messageWithAnUnparsableBody_isEmpty() {
        Frame frame = StompFrames.parse(messageFrame("m", "not json")).orElseThrow();

        assertThat(EpicConnectMessage.from(frame)).isEmpty();
    }
}
