package fr.enimaloc.catapult.service.title;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class TitleTemplateTest {

    private static final Supplier<Optional<String>> UNREAD = () -> {
        throw new AssertionError("a title was read that no chosen branch uses");
    };

    private static TitleContext context(Map<String, Boolean> conditions) {
        return new TitleContext("Doom", "DOOM", UNREAD, UNREAD, conditions, null, null);
    }

    private static Optional<String> render(String template, Map<String, Boolean> conditions) {
        return TitleTemplate.render(template, context(conditions), true);
    }

    @Test
    void noTernary_rendersExactlyAsBefore() {
        Optional<String> none = Optional.empty();
        for (String template : new String[]{"Live {{GAME}} in {{CATEGORY}}", "{{game}} {{ GAME }} {GAME} {{STREAMER}} $1",
                "  plain title  ", "[[x]] : y ? z", "{{GAME"}) {
            assertThat(TitleTemplate.render(template, context(Map.of()), true))
                    .as(template)
                    .isEqualTo(StreamTitleService.render(template, "Doom", "DOOM", none, none));
        }
        assertThat(render("Live {{GAME}} in {{CATEGORY}}", Map.of())).contains("Live Doom in DOOM");
    }

    @Test
    void ternary_picksTheBranch() {
        String template = "{{HAS_GAME ? [[A {{GAME}}]] : [[B]]}} !";
        assertThat(render(template, Map.of("HAS_GAME", true))).contains("A Doom !");
        assertThat(render(template, Map.of("HAS_GAME", false))).contains("B !");
        assertThat(render(template, Map.of())).as("an absent condition is false").contains("B !");
    }

    @Test
    void operators_notAndOrParentheses() {
        Map<String, Boolean> c = new HashMap<>(Map.of("HAS_GAME", true, "IS_CUSTOM", false, "HAS_TW", true));
        assertThat(render("{{!IS_CUSTOM ? [[y]] : [[n]]}}", c)).contains("y");
        assertThat(render("{{IS_CUSTOM || HAS_GAME && HAS_TW ? [[y]] : [[n]]}}", c)).as("&& before ||").contains("y");
        assertThat(render("{{(IS_CUSTOM || HAS_GAME) && !HAS_TW ? [[y]] : [[n]]}}", c)).contains("n");
        assertThat(render("{{ ! ( IS_CUSTOM ) ? [[y]] : [[n]] }}", c)).as("spaces allowed").contains("y");
    }

    @Test
    void nested_ternaryInABranch() {
        String template = "{{IS_CUSTOM ? [[custom]] : [[{{DROP_ENABLED ? [[[Drops] {{GAME}}]] : [[{{GAME}}: soon]]}}]]}}";
        assertThat(render(template, Map.of("IS_CUSTOM", true))).contains("custom");
        assertThat(render(template, Map.of("DROP_ENABLED", true))).contains("[Drops] Doom");
        assertThat(render(template, Map.of())).contains("Doom: soon");
    }

    @Test
    void emptyBranch_isEmpty() {
        assertThat(render("Live{{DROP_ENABLED ? [[ [Drops]]] : [[]]}}", Map.of())).contains("Live");
        assertThat(render("Live{{DROP_ENABLED ? [[ [Drops]]] : [[]]}}", Map.of("DROP_ENABLED", true)))
                .contains("Live [Drops]");
    }

    @Test
    void malformed_staysRaw_theRestRendered() {
        assertThat(render("{{GAME}} {{HAS_GAME ? [[a]]}}", Map.of("HAS_GAME", true)))
                .contains("Doom {{HAS_GAME ? [[a]]}}");
        assertThat(render("{{UNKNOWN ? [[a]] : [[b]]}} {{GAME}}", Map.of()))
                .contains("{{UNKNOWN ? [[a]] : [[b]]}} Doom");
        assertThat(render("{{HAS_GAME ? [[a]] : [[b}} {{GAME}}", Map.of())).as("]] missing")
                .contains("{{HAS_GAME ? [[a]] : [[b}} Doom");
        assertThat(render("{{HAS_GAME && ? [[a]] : [[b]]}}", Map.of())).contains("{{HAS_GAME && ? [[a]] : [[b]]}}");
    }

    @Test
    void literalBrackets_outsideATernary_stayText() {
        assertThat(render("[[x]] : y ? z {{GAME}}", Map.of())).contains("[[x]] : y ? z Doom");
    }

    @Test
    void titleOnlyReadInTheChosenBranch() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<Optional<String>> title = () -> {
            reads.incrementAndGet();
            return Optional.of("Chill");
        };
        TitleContext ctx = new TitleContext("Doom", "DOOM", title, UNREAD, Map.of("HAS_GAME", true), null, null);

        assertThat(TitleTemplate.render("{{HAS_GAME ? [[{{GAME}}]] : [[{{TITLE}}]]}}", ctx, true)).contains("Doom");
        assertThat(reads).hasValue(0);
        assertThat(TitleTemplate.render("{{!HAS_GAME ? [[x]] : [[{{TITLE}} | {{GAME}}]]}}", ctx, true))
                .contains("Chill | Doom");
    }

    @Test
    void unreadableTitleInChosenBranch_isEmpty() {
        TitleContext ctx = new TitleContext("Doom", "DOOM", Optional::empty, Optional::empty, Map.of(), null, null);

        assertThat(TitleTemplate.render("{{HAS_GAME ? [[x]] : [[{{ORIGINAL_TITLE}}]]}}", ctx, true)).isEmpty();
        assertThat(TitleTemplate.render("{{!HAS_GAME ? [[x]] : [[{{ORIGINAL_TITLE}}]]}}", ctx, true)).contains("x");
    }

    @Test
    void conditionsOff_leavesTernariesRaw_butReplacesVariables() {
        assertThat(TitleTemplate.render("{{GAME}} {{HAS_GAME ? [[a]] : [[b]]}}", context(Map.of("HAS_GAME", true)), false))
                .contains("Doom {{HAS_GAME ? [[a]] : [[b]]}}");
    }

    @Test
    void dropVariables_emptyWithoutACampaign() {
        TitleContext with = new TitleContext("Doom", "DOOM", UNREAD, UNREAD, Map.of("DROP_ENABLED", true),
                "SEASON AURORA", "25/11");
        assertThat(TitleTemplate.render("{{DROP_ENABLED ? [[Drops: {{DROP_NAME}} until {{DROP_END}}]] : [[]]}}", with, true))
                .contains("Drops: SEASON AURORA until 25/11");
        assertThat(TitleTemplate.render("x{{DROP_NAME}}{{DROP_END}}", context(Map.of()), true)).contains("x");
    }

    @Test
    void theResult_isCutAndNeverEmpty() {
        assertThat(render("{{HAS_GAME ? [[" + "x".repeat(150) + "]] : [[]]}}", Map.of("HAS_GAME", true)))
                .hasValueSatisfying(t -> assertThat(t).hasSize(140));
        assertThat(render("{{HAS_GAME ? [[x]] : [[  ]]}}", Map.of())).isEmpty();
    }
}
