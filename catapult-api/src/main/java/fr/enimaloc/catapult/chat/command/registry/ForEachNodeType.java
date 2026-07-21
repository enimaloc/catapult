package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.ForEachNode;
import org.springframework.stereotype.Component;

@Component
public class ForEachNodeType implements StructuralNodeType {
    @Override
    public String keyword() {
        return "for";
    }

    @Override
    public boolean handles(CommandNode node) {
        return node instanceof ForEachNode;
    }
}
