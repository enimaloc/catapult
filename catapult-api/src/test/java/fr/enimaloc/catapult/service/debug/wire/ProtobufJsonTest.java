package fr.enimaloc.catapult.service.debug.wire;

import static org.assertj.core.api.Assertions.assertThat;

import bgs.protocol.EntityProto.EntityId;
import bgs.protocol.authentication.v2.client.AuthenticationServiceOuterClass.VerifyAuthTokenRequest;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.UpdateFriendStateNotification;
import bgs.protocol.friends.v2.client.FriendsTypes.FriendStateAssignment;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProtobufJsonTest {

    @Test
    void fieldsAreKeyedByNameWith64BitIntegersAsStrings() {
        UpdateFriendStateNotification message = UpdateFriendStateNotification.newBuilder()
                .setAgentAccountId(5)
                .addAssignments(FriendStateAssignment.newBuilder().setId(42).setLevel(3))
                .build();

        assertThat(ProtobufJson.of(message).toString())
                .isEqualTo("{\"agent_account_id\":\"5\",\"assignments\":[{\"id\":\"42\",\"level\":3}]}");
    }

    @Test
    void unsigned64BitValuesStayUnsigned() {
        EntityId id = EntityId.newBuilder().setHigh(-1L).setLow(42L).build();

        assertThat(ProtobufJson.of(id).toString())
                .isEqualTo("{\"high\":\"18446744073709551615\",\"low\":\"42\"}");
    }

    @Test
    void anAccountIdCarriedByAnEntityIdIsAKey() {
        EntityId id = EntityId.newBuilder().setHigh(0x100000000000000L).setLow(42L).build();

        assertThat(WireKeys.of(ProtobufJson.of(id))).contains("id:42");
    }

    @Test
    void bytesArePrintedAsTextWhenPrintableElseAsHex() {
        VerifyAuthTokenRequest text = VerifyAuthTokenRequest.newBuilder()
                .setAuthToken(ByteString.copyFrom("Streamer#1234", StandardCharsets.UTF_8)).buildPartial();
        VerifyAuthTokenRequest binary = VerifyAuthTokenRequest.newBuilder()
                .setAuthToken(ByteString.copyFrom(new byte[] {0, (byte) 0xFF})).buildPartial();

        assertThat(ProtobufJson.of(text).get("auth_token").asText()).isEqualTo("Streamer#1234");
        assertThat(ProtobufJson.of(binary).get("auth_token").get("bytes").asText()).isEqualTo("00ff");
    }

    @Test
    void enumsArePrintedByName() {
        FieldDescriptorProto field = FieldDescriptorProto.newBuilder()
                .setName("x").setNumber(1).setType(Type.TYPE_STRING).build();

        assertThat(ProtobufJson.of(field).toString())
                .isEqualTo("{\"name\":\"x\",\"number\":1,\"type\":\"TYPE_STRING\"}");
    }
}
