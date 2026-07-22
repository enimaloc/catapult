package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslGenerator;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bridges the browser's Blocks tab to the (Java-only) text DSL grammar so the editor modal
 * can convert text&lt;-&gt;AST without duplicating {@link CommandDslParser}/{@link CommandDslGenerator}
 * in JavaScript.
 */
@RestController
public class ApiChatCommandDslController {

    private static final CommandDslParser PARSER = new CommandDslParser();
    private static final CommandDslGenerator GENERATOR = new CommandDslGenerator();
    private static final NodeJsonCodec CODEC = new NodeJsonCodec();

    @PostMapping("/api/chat-commands/dsl/text-to-ast")
    public Map<String, String> textToAst(@RequestBody Map<String, String> body) {
        return Map.of("ast", CODEC.toJson(PARSER.parse(body.get("text"))));
    }

    @PostMapping("/api/chat-commands/dsl/ast-to-text")
    public Map<String, String> astToText(@RequestBody Map<String, String> body) {
        return Map.of("text", GENERATOR.generate(CODEC.fromJson(body.get("ast"))));
    }
}
