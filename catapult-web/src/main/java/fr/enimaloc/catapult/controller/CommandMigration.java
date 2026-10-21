package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Integrations tab's card moving a streamer's catapult chat commands to another chat bot: for
 * each tool, what to set up for each command. Every command made from a preset becomes a read of
 * the user API's summary ({@code /api/user/summary/{token}}, relayed on this site's origin): the
 * field standing for what the preset said, read from the JSON by the tools that can, or asked for
 * alone with {@code ?f=} by those that can't (StreamElements, Wizebot).
 *
 * <p>A command the API has nothing for ({@code !setgame}, the streamer's own) has no row: it is
 * listed apart.
 *
 * @param tools     one per chat bot, in the menu's order
 * @param unmatched the commands the API gives nothing for
 */
public record CommandMigration(List<Tool> tools, List<String> unmatched) {

    /**
     * One thing to paste or type, named by {@code channel.commands.migration.line.<label>}.
     *
     * @param label what it is (a chat line, the URL to call...)
     */
    public record Line(String label, String value) {}

    /**
     * One command for one tool.
     *
     * @param command the command, "!" included
     * @param role    who may run it (a SenderRole name)
     */
    public record Row(String command, String role, List<Line> lines) {}

    /**
     * A chat bot: {@code id} names its i18n keys ({@code channel.commands.migration.<id>.*}),
     * whose steps run from {@code step1} to {@code step<steps>}.
     */
    public record Tool(String id, int steps, List<Row> rows) {}

    /** What the summary gives in place of each preset's answer, as a dotted path into its JSON. */
    static final Map<String, String> FIELDS = Map.of(
            "game", "name",
            "description", "description",
            "triggers", "tws",
            "store", "storeUrl",
            "igdb", "igdbUrl",
            "release", "releaseDate.releaseDate");

    private static final Map<String, String> NIGHTBOT_LEVELS = Map.of("VIEWERS", "everyone",
            "FOLLOWERS", "everyone", "SUBS", "subscriber", "VIP", "twitch_vip",
            "MODERATOR", "moderator", "BROADCASTER", "owner");
    private static final Map<String, Integer> STREAMELEMENTS_LEVELS = Map.of("VIEWERS", 100,
            "FOLLOWERS", 100, "SUBS", 250, "VIP", 400, "MODERATOR", 500, "BROADCASTER", 1500);

    /**
     * The card for {@code commands}, read from the summary at {@code summaryUrl} (absolute); null
     * when there is nothing to move.
     *
     * @param empty what Nightbot posts when the field is empty (nothing played, no store...)
     */
    static CommandMigration of(List<EnabledChatCommandDto> commands, String summaryUrl, String empty) {
        if (commands.isEmpty() || summaryUrl == null) return null;
        List<Command> matched = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (EnabledChatCommandDto dto : commands) {
            String field = dto.presetKey() == null ? null : FIELDS.get(dto.presetKey());
            if (field == null) unmatched.add(dto.name());
            else matched.add(new Command(dto.name(), dto.permission(), field, summaryUrl));
        }
        return new CommandMigration(List.of(
                tool("twitchat", 4, matched, c -> List.of(
                        new Line("url", c.url()),
                        new Line("jsonpath", "$." + c.field()),
                        new Line("message", "{" + c.placeholder() + "}"))),
                tool("streamerbot", 3, matched, c -> List.of(
                        new Line("url", c.url()),
                        new Line("message", "%catapult." + c.field() + "%"))),
                tool("nightbot", 3, matched, c -> List.of(new Line("chat", "!commands add " + c.name()
                        + " -ul=" + NIGHTBOT_LEVELS.getOrDefault(c.role(), "everyone")
                        + " $(eval const r = $(urlfetch json " + c.url() + "); " + c.jsRead("r") + " || \""
                        + jsString(empty) + "\")"))),
                tool("streamelements", 3, matched, c -> {
                    int level = STREAMELEMENTS_LEVELS.getOrDefault(c.role(), 100);
                    List<Line> lines = new ArrayList<>();
                    lines.add(new Line("chat", "!command add " + c.name() + " $(customapi " + c.fieldUrl() + ")"));
                    if (level != 100) lines.add(new Line("chat", "!command options " + c.name() + " -level " + level));
                    return lines;
                }),
                tool("wizebot", 3, matched, c -> List.of(new Line("text", "$urlcall(" + c.fieldUrl() + ",1)")))),
                unmatched);
    }

    private static Tool tool(String id, int steps, List<Command> commands,
                             java.util.function.Function<Command, List<Line>> lines) {
        return new Tool(id, steps, commands.stream().map(c -> new Row(c.name(), c.role(), lines.apply(c))).toList());
    }

    /** {@code text} inside a JS double-quoted string. */
    private static String jsString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A command made from a preset, and the summary field standing for it. */
    private record Command(String name, String role, String field, String url) {
        /** The field alone, as plain text, for the tools that can't read JSON. */
        String fieldUrl() {
            return url + "?f=" + field;
        }

        /** Twitchat's placeholder for the field: its path, uppercased, dots dropped. */
        String placeholder() {
            return field.replace(".", "_").toUpperCase(Locale.ROOT);
        }

        /**
         * The field read off {@code variable} in JS, each step guarded ({@code r.a && r.a.b}), and
         * nothing while nothing is played.
         */
        String jsRead(String variable) {
            StringBuilder read = new StringBuilder(variable + ".inGame");
            String path = variable;
            for (String part : field.split("\\.")) {
                path += "." + part;
                read.append(" && ").append(path);
            }
            return read.toString();
        }
    }
}
