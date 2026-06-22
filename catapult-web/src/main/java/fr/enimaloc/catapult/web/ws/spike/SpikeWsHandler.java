package fr.enimaloc.catapult.web.ws.spike;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpikeWsHandler extends TextWebSocketHandler {
    private final DispatcherServlet dispatcherServlet;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String path = message.getPayload().trim();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        dispatcherServlet.service(req, resp);
        String html = resp.getContentAsString();
        log.info("spike: path={} status={} bytes={}", path, resp.getStatus(), html.length());
        session.sendMessage(new TextMessage(html));
    }
}
