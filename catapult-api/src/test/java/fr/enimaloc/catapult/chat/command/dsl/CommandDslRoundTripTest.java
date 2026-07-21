package fr.enimaloc.catapult.chat.command.dsl;

import fr.enimaloc.catapult.chat.command.ast.CommandAst;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDslRoundTripTest {

    private final CommandDslParser parser = new CommandDslParser();
    private final CommandDslGenerator generator = new CommandDslGenerator();

    @Test
    void placeholderRoundTrips() {
        String source = "Now playing {game#name}!";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void serviceCallWithLiteralArgRoundTrips() {
        String source = "{igdb#getGame(\"Valorant\")}";
        assertThat(generator.generate(parser.parse(source))).isEqualTo(source);
    }

    @Test
    void serviceCallWithPlaceholderArgRoundTrips() {
        String source = "{steam#getPrice(game#store#steam)}";
        CommandAst ast = parser.parse(source);
        assertThat(generator.generate(ast)).isEqualTo(source);
    }
}
