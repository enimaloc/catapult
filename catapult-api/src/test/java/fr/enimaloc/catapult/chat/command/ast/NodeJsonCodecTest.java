package fr.enimaloc.catapult.chat.command.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeJsonCodecTest {

    private final NodeJsonCodec codec = new NodeJsonCodec();

    @Test
    void roundTripsThroughJson() {
        CommandAst ast = new CommandAst(List.of(
            new VarDeclStatement("msg", ValueType.STRING, new LiteralExpr("", ValueType.STRING)),
            new ConcatStatement("msg", new LiteralExpr("Now playing ", ValueType.STRING)),
            new ConcatStatement("msg", new ContextGetExpr("game#name")),
            new IfStatement(
                new BinaryExpr(new ContextGetExpr("game#name"), "==", new LiteralExpr("Valorant", ValueType.STRING)),
                List.of(new ConcatStatement("msg", new LiteralExpr(" (ranked)", ValueType.STRING))),
                List.of()),
            new ForEachStatement("f", "fallbacks", List.of(new ConcatStatement("msg", new VarRefExpr("f")))),
            new PrintStatement(new VarRefExpr("msg")),
            new PrintStatement(new ServiceCallExpr("igdb", "getGame", List.of(new ContextGetExpr("game#name"))))
        ));

        String json = codec.toJson(ast);
        CommandAst restored = codec.fromJson(json);

        assertThat(restored).isEqualTo(ast);
        assertThat(json).contains("\"statements\"");
    }

    @Test
    void jsonRootKeyIsStatementsNotNodes() {
        CommandAst ast = new CommandAst(List.of(new PrintStatement(new LiteralExpr("hi", ValueType.STRING))));
        String json = codec.toJson(ast);
        assertThat(json).contains("\"statements\":[");
        assertThat(json).doesNotContain("\"nodes\":");
    }

    @Test
    void roundTripsObjectLiteralAndPropertyGet() {
        java.util.Map<String, Expression> properties = new java.util.LinkedHashMap<>();
        properties.put("name", new LiteralExpr("Valorant", ValueType.STRING));
        properties.put("price", new LiteralExpr("29.99", ValueType.NUMBER));
        properties.put("meta", new ObjectLiteralExpr(java.util.Map.of("free", new LiteralExpr("true", ValueType.BOOLEAN))));

        CommandAst ast = new CommandAst(List.of(
            new VarDeclStatement("game", ValueType.OBJECT, new ObjectLiteralExpr(properties)),
            new AssignStatement("msg", new PropertyGetExpr(new VarRefExpr("game"), "name"))
        ));

        String json = codec.toJson(ast);
        CommandAst restored = codec.fromJson(json);

        assertThat(restored).isEqualTo(ast);
        assertThat(json).contains("\"object-literal\"");
        assertThat(json).contains("\"property-get\"");
    }

    @Test
    void roundTripsSettingGetExpr() {
        CommandAst ast = new CommandAst(List.of(
            new AssignStatement("msg", new SettingGetExpr("language"))
        ));

        String json = codec.toJson(ast);
        CommandAst restored = codec.fromJson(json);

        assertThat(restored).isEqualTo(ast);
        assertThat(json).contains("\"setting-get\"");
        assertThat(json).contains("\"language\"");
    }

    @Test
    void roundTripsArgGetExpr() {
        CommandAst ast = new CommandAst(List.of(
            new AssignStatement("msg", new ArgGetExpr(0))
        ));

        String json = codec.toJson(ast);
        CommandAst restored = codec.fromJson(json);

        assertThat(restored).isEqualTo(ast);
        assertThat(json).contains("\"arg-get\"");
        assertThat(json).contains("\"index\":0");
    }
}
