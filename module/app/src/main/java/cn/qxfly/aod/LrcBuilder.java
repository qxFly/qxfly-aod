package cn.qxfly.aod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.model.Line;
import com.hchen.superlyricapi.model.Lyric;

import java.util.List;

/**
 * Converts a SuperLyric Lyric document into the LRC text format that
 * com.miui.maml.elements.MusicLyricParser.parseLyric(String) consumes.
 *
 * <p>Hard requirements from the parser (verified by decompilation of
 * target APK DEV-2455.1.0.1-09282249):
 * <ul>
 *   <li>Lines are split on "\r\n" exactly — LF alone yields one unparsable line.</li>
 *   <li>Each line is "[mm:ss.cc]text"; parseEntity splits the timestamp on ':'
 *       and treats the last segment as seconds (multiplied by 1000).</li>
 *   <li>Timestamps must be &lt; 18000000 ms (MAX_VALID_TIME) or the line is dropped.</li>
 *   <li>The parser strips tags like "&lt;0:00:00&gt;" and runs Html.fromHtml on each
 *       line after appending "&lt;br/&gt;", so raw "&lt;" or "&amp;" in text will be
 *       interpreted as HTML. Escape "&lt;" and "&amp;" before injecting.</li>
 *   <li>Zero parsed lines -> parseLyric returns null -> AOD resets lyric vars.</li>
 * </ul>
 */
public final class LrcBuilder {

    private static final long MAX_VALID_TIME_MS = 18_000_000L;

    private LrcBuilder() {
    }

    /**
     * Builds an LRC string. Returns null when nothing usable remains, so the
     * caller can distinguish "no lyrics" from "lyrics exist".
     */
    @Nullable
    public static String build(@Nullable Lyric doc) {
        if (doc == null) {
            return null;
        }
        List<Line> lines = doc.getLines();
        if (lines == null || lines.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(lines.size() * 40);
        int kept = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line == null) {
                continue;
            }
            String text = sanitize(line.getText());
            long start = line.getStart();
            if (start < 0 || start >= MAX_VALID_TIME_MS || text.isEmpty()) {
                continue;
            }
            appendTimestamp(sb, start);
            sb.append(text);
            sb.append("\r\n");
            kept++;
        }
        // parseLyric returns null on empty input -> AOD resets lyric vars.
        return kept > 0 ? sb.toString() : null;
    }

    /** Appends "[mm:ss.cc]" using centiseconds, matching the parser's 2-digit expectation. */
    private static void appendTimestamp(@NonNull StringBuilder sb, long ms) {
        long minutes = ms / 60_000L;
        long seconds = (ms % 60_000L) / 1000L;
        long centis = (ms % 1000L) / 10L;
        sb.append('[');
        if (minutes < 10) {
            sb.append('0');
        }
        sb.append(minutes).append(':');
        if (seconds < 10) {
            sb.append('0');
        }
        sb.append(seconds).append('.');
        if (centis < 10) {
            sb.append('0');
        }
        sb.append(centis).append(']');
    }

    /**
     * The AOD parser runs Html.fromHtml(line + "<br/>") in decorate(). Any literal
     * '&lt;' or '&amp;' corrupts the rendered line, so escape both. Text is also
     * trimmed; empty lines are dropped upstream by the caller loop.
     *
     * <p>Ampersand MUST be escaped first: doing '<' first turned "a&lt;b" into
     * "a&amp;lt;b", which Html.fromHtml renders as the literal text "&lt;"
     * instead of "<".
     */
    @NonNull
    private static String sanitize(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String out = text.trim();
        if (out.indexOf('&') >= 0) {
            out = out.replace("&", "&amp;");
        }
        if (out.indexOf('<') >= 0) {
            out = out.replace("<", "&lt;");
        }
        return out;
    }
}
