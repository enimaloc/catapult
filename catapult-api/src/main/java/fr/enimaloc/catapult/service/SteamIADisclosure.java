package fr.enimaloc.catapult.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public class SteamIADisclosure {
    private static final Map<SteamLanguage, Pattern> PATTERNS = Map.ofEntries(
            entry(SteamLanguage.SCHINESE, "AI 生成内容披露"),
            entry(SteamLanguage.TCHINESE, "AI 生成內容聲明"),
            entry(SteamLanguage.JAPANESE, "AI生成コンテンツの開示"),
            entry(SteamLanguage.KOREANA, "AI 생성 콘텐츠 사용 공개"),
            entry(SteamLanguage.THAI, "การเปิดเผยข้อมูลเกี่ยวกับเนื้อหาที่สร้างด้วย AI"),
            entry(SteamLanguage.INDONESIAN, "Pernyataan Konten Buatan AI"),
            entry(SteamLanguage.BULGARIAN, "Оповестяване за съдържание, генерирано от ИИ"),
            entry(SteamLanguage.CZECH, "Informace o obsahu vytvářeném AI"),
            entry(SteamLanguage.DANISH, "Meddelelse om AI-genereret indhold"),
            entry(SteamLanguage.GERMAN, "Offenlegung von KI-generierten Inhalten"),
            entry(SteamLanguage.ENGLISH, "AI Generated Content Disclosure"),
            entry(SteamLanguage.SPANISH, "Información sobre contenido generado por IA"),
            entry(SteamLanguage.GREEK, "Γνωστοποίηση περιεχομένου που δημιουργήθηκε από τεχνητή νοημοσύνη (AI)"),
            entry(SteamLanguage.FRENCH, "Notification de contenu généré par IA"),
            entry(SteamLanguage.ITALIAN, "Divulgazione dei contenuti generati dall'IA"),
            entry(SteamLanguage.HUNGARIAN, "Nyilatkozat MI generálta tartalomról"),
            entry(SteamLanguage.DUTCH, "Informatie over door AI gegenereerde inhoud"),
            entry(SteamLanguage.NORWEGIAN, "Opplysning om AI-generert innhold"),
            entry(SteamLanguage.POLISH, "Oświadczenie w sprawie treści generowanych przez SI"),
            entry(SteamLanguage.PORTUGUESE, "Divulgação de conteúdo gerado por IA"),
            entry(SteamLanguage.BRAZILIAN, "Divulgação de conteúdo gerado por IA"),
            entry(SteamLanguage.ROMANIAN, "Informații despre conținutul generat de IA"),
            entry(SteamLanguage.RUSSIAN, "Информация об ИИ-контенте"),
            entry(SteamLanguage.FINNISH, "Tiedote tekoälysisällöstä"),
            entry(SteamLanguage.SWEDISH, "Upplysning om AI-genererat innehåll"),
            entry(SteamLanguage.TURKISH, "Yapay Zekâ İçeriği Açıklaması"),
            entry(SteamLanguage.VIETNAMESE, "Công bố về nội dung tạo bởi AI"),
            entry(SteamLanguage.UKRAINIAN, "Розкриття інформації щодо вмісту, згенерованого ШІ"),
            entry(SteamLanguage.ARABIC, "تنويه بأن هذا محتوى مولد بالذكاء الاصطناعي")
    );

    private static Map.Entry<SteamLanguage, Pattern> entry(SteamLanguage language, String literalText) {
        return Map.entry(language, Pattern.compile(Pattern.quote(literalText)));
    }

    public static boolean hasDisclosure(String html) {
        return Arrays.stream(SteamLanguage.values())
                .map(l -> hasDisclosure(html, l))
                .filter(b -> b)
                .findFirst()
                .orElse(false);
    }

    public static boolean hasDisclosure(String html, Locale locale) {
        return hasDisclosure(html, SteamLanguage.fromLocale(locale));
    }

    public static boolean hasDisclosure(String html, SteamLanguage language) {
        Pattern pattern = PATTERNS.getOrDefault(language, PATTERNS.get(SteamLanguage.ENGLISH));
        return pattern.matcher(html).find();
    }

    /**
     * Extracts the developer's own description of their game's AI-generated content, from the
     * {@code #game_area_content_descriptors} block (the {@code <i>...</i>} paragraph under the
     * disclosure heading). The div's id is stable across Steam's locales, unlike the heading text
     * — but Steam reuses that same id for the unrelated "Mature Content Description" block, and
     * page order between the two is not guaranteed, so {@code getElementById} (first match only)
     * isn't safe here: every div sharing the id is checked and matched by its {@code <h2>} instead.
     */
    public static Optional<String> extractDeveloperDescription(String html) {
        if (!hasDisclosure(html)) return Optional.empty();
        Document document = Jsoup.parse(html);
        for (Element descriptors : document.select("#game_area_content_descriptors")) {
            Element heading = descriptors.selectFirst("h2");
            if (heading == null || !hasDisclosure(heading.outerHtml())) continue;
            Element description = descriptors.selectFirst("p i");
            if (description == null) continue;
            String text = description.text().trim();
            if (!text.isEmpty()) return Optional.of(text);
        }
        return Optional.empty();
    }
}
