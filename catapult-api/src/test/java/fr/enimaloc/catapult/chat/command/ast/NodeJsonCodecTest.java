package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeJsonCodecTest {

    private final NodeJsonCodec codec = new NodeJsonCodec();

    @Test
    void roundTripsThroughJson() {
        CommandAst ast = new CommandAst(List.of(
            new LiteralNode("Hello "),
            new PlaceholderNode("game#name"),
            new IfNode(new PlaceholderNode("tw#active"), "==", new LiteralNode("x"),
                List.of(new LiteralNode("yes")), List.of(new LiteralNode("no"))),
            new ForEachNode("f", "fallbacks", List.of(new PlaceholderNode("f"))),
            new ServiceCallNode("igdb", "getGame", List.of(new PlaceholderNode("game#name")))
        ));

        String json = codec.toJson(ast);
        CommandAst restored = codec.fromJson(json);

        assertThat(restored).isEqualTo(ast);
    }
}
