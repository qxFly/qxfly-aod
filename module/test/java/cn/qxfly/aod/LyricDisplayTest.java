package cn.qxfly.aod;

/**
 * The overlay's render-cache marker. A too-sensitive marker costs a structural
 * refresh every second; a too-coarse one freezes the previous line on screen.
 */
final class LyricDisplayTest {

    private LyricDisplayTest() {
    }

    static void run() {
        LyricDisplay a = new LyricDisplay();
        a.line = "line one";
        a.translation = "译文";
        a.next = "line two";
        a.title = "Song";
        a.artist = "Artist";
        a.playing = true;

        LyricDisplay b = a.copy();
        T.eq("copy has the same marker", a.signature(false), b.signature(false));

        b.line = "line two";
        T.ok("line change is detected", !a.signature(false).equals(b.signature(false)));

        b = a.copy();
        b.translation = null;
        T.ok("translation change is detected", !a.signature(false).equals(b.signature(false)));

        b = a.copy();
        b.next = null;
        T.ok("next-line change is detected", !a.signature(false).equals(b.signature(false)));

        b = a.copy();
        b.paused = true;
        T.ok("pause transition is detected", !a.signature(false).equals(b.signature(false)));

        // Position must NOT invalidate the marker unless progress is drawn.
        b = a.copy();
        b.positionMs = 99_000L;
        b.durationMs = 200_000L;
        T.eq("position ignored without progress",
                a.signature(false), b.signature(false));
        T.ok("position honoured with progress",
                !a.signature(true).equals(b.signature(true)));

        // "unknown position" must never render as a negative timestamp.
        T.eq("unknown time placeholder", "-", LyricDisplay.time(-1L));
        T.eq("zero time", "0:00", LyricDisplay.time(0L));
        T.eq("minutes rollover", "1:05", LyricDisplay.time(65_000L));
        T.eq("seconds are padded", "0:07", LyricDisplay.time(7_400L));
    }
}
