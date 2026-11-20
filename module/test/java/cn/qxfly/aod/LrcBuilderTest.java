package cn.qxfly.aod;

import com.hchen.superlyricapi.model.Line;
import com.hchen.superlyricapi.model.Lyric;

import java.util.ArrayList;
import java.util.List;

/**
 * LRC generation for the native MAML parser. The constraints encoded here were
 * found by decompiling the target APK (see the class javadoc of LrcBuilder):
 * CRLF separators, [mm:ss.cc] timestamps, escaped HTML entities.
 */
final class LrcBuilderTest {

    private LrcBuilderTest() {
    }

    static void run() {
        T.eq("null document -> null", null, LrcBuilder.build(null));

        // (An empty line list is impossible by construction: Lyric rejects it.)

        // Timestamp formatting: [mm:ss.cc], zero padded, centiseconds.
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("hello", 1000L, 2000L));
        lines.add(new Line("later", 61_230L, 62_000L));
        String lrc = LrcBuilder.build(new Lyric("id", "t", "a", "", 120_000L, lines));
        T.eq("timestamp + CRLF",
                "[00:01.00]hello\r\n[01:01.23]later\r\n", lrc);

        // Out-of-range timestamps are dropped by the parser anyway: skip them
        // so a bogus value cannot poison the whole document.
        List<Line> bad = new ArrayList<>();
        bad.add(new Line("negative", -1L, 0L));
        bad.add(new Line("huge", 18_000_000L, 18_001_000L));
        bad.add(new Line("ok", 0L, 1000L));
        T.eq("invalid timestamps dropped",
                "[00:00.00]ok\r\n", LrcBuilder.build(new Lyric("id", "t", "a", "", 0L, bad)));

        // Blank lines carry no information and would render as empty pills.
        List<Line> blanks = new ArrayList<>();
        blanks.add(new Line("   ", 0L, 1000L));
        blanks.add(new Line("", 1000L, 2000L));
        T.eq("blank lines -> null",
                null, LrcBuilder.build(new Lyric("id", "t", "a", "", 0L, blanks)));

        // The parser runs Html.fromHtml on every line, so entities must be
        // escaped - and "&" BEFORE "<", or "&lt;" gets double-escaped.
        List<Line> html = new ArrayList<>();
        html.add(new Line("a<b&c", 0L, 1000L));
        T.eq("html entities escaped once",
                "[00:00.00]a&lt;b&amp;c\r\n",
                LrcBuilder.build(new Lyric("id", "t", "a", "", 0L, html)));

        // Already-escaped-looking input must not be re-escaped into a mess.
        List<Line> amp = new ArrayList<>();
        amp.add(new Line("A&B", 0L, 1000L));
        T.eq("ampersand escaped",
                "[00:00.00]A&amp;B\r\n",
                LrcBuilder.build(new Lyric("id", "t", "a", "", 0L, amp)));
    }
}
