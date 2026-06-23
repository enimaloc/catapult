package fr.enimaloc.catapult.web.ws.codec;

import fr.enimaloc.catapult.web.ws.codec.msg.WsIncoming;
import fr.enimaloc.catapult.web.ws.codec.msg.WsOutgoing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonMessageCodec {

    private final ObjectMapper mapper;

    public JsonMessageCodec() {
        this(JsonMapper.builder().build());
    }

    @Autowired
    public JsonMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(WsOutgoing msg) throws JacksonException {
        return mapper.writeValueAsString(msg);
    }

    /**
     * Internal helper used for roundtrip tests on incoming messages. Uses the same ObjectMapper, so
     * the polymorphic discriminator is preserved.
     */
    public String encode(WsIncoming msg) throws JacksonException {
        return mapper.writeValueAsString(msg);
    }

    public WsIncoming decodeIncoming(String json) throws JacksonException {
        return mapper.readValue(json, WsIncoming.class);
    }
}
