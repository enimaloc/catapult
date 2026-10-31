package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import fr.enimaloc.catapult.chat.command.ast.NodeJsonCodec;
import fr.enimaloc.catapult.chat.command.dsl.CommandDslParser;
import org.springframework.stereotype.Component;

@Component
public class LegacyTemplateConverter {

    private final CommandDslParser parser = new CommandDslParser();
    private final NodeJsonCodec codec = new NodeJsonCodec();

    public String toAstJson(String template) {
        CommandAst ast = parser.parse(template);
        return codec.toJson(ast);
    }
}
