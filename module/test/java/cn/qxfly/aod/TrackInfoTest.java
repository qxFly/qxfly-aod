package cn.qxfly.aod;

/**
 * Track title/artist merging between the SuperLyric payload and the platform
 * media session.
 *
 * <p>Two real-world traps drive these cases (盐歌 / com.salt.music): the
 * payload keeps the PREVIOUS track's title/artist, and the session's
 * METADATA_KEY_TITLE is the CURRENT LYRIC LINE. Both were displayed to the
 * user as the wrong song title on the AOD.
 */
final class TrackInfoTest {

    private TrackInfoTest() {
    }

    static void run() {
        // 1. Both sources agree (healthy player): payload wins unchanged.
        TrackInfo i = TrackInfo.resolve("奔跑", "羽·泉", "奔跑", "羽·泉", "作曲 : 可乐");
        T.eq("agreement keeps payload title", "奔跑", i.title);
        T.eq("agreement keeps payload artist", "羽·泉", i.artist);

        // 2. Stale payload + session title is the lyric line (盐歌 pattern):
        // the wrong song must disappear, artist-only row remains.
        i = TrackInfo.resolve("奔跑", "羽·泉",
                "受挫过却未曾心甘", "年轻不服输 [mqms2] - 张恒远",
                "受挫过却未曾心甘");
        T.eq("stale payload title dropped", null, i.title);
        T.eq("session artist replaces stale one",
                "年轻不服输 [mqms2] - 张恒远", i.artist);

        // 3. Stale payload + a real session title: session wins both fields.
        i = TrackInfo.resolve("雨一直下", "张宇", "年轻就绝不服输", "年轻不服输 - 张恒远",
                "仍想穿越命运浩瀚");
        T.eq("stale payload loses to session title", "年轻就绝不服输", i.title);
        T.eq("stale payload loses to session artist", "年轻不服输 - 张恒远", i.artist);

        // 4. Session title is a line, but payload and session AGREE on the
        // artist: payload is fresh, keep it.
        i = TrackInfo.resolve("奔跑", "羽·泉", "作曲 : 可乐", "羽·泉", "作曲 : 可乐");
        T.eq("fresh payload kept", "奔跑", i.title);
        T.eq("fresh payload artist kept", "羽·泉", i.artist);

        // 5. No payload metadata: session fills both (original fallback).
        i = TrackInfo.resolve(null, null, "Song", "Artist", null);
        T.eq("session fills empty payload title", "Song", i.title);
        T.eq("session fills empty payload artist", "Artist", i.artist);

        // 6. Payload has only an artist and the session knows nothing:
        // the artist must survive.
        i = TrackInfo.resolve(null, "羽·泉", null, null, null);
        T.eq("orphan payload artist kept", null, i.title);
        T.eq("orphan payload artist value", "羽·泉", i.artist);

        // 7. Session title equals the displayed line but the session has no
        // artist to cross-check: payload stays (it is all we have).
        i = TrackInfo.resolve("奔跑", "羽·泉", "暗い夜の", null, "暗い夜の");
        T.eq("line-as-title ignored, payload kept", "奔跑", i.title);

        // 8. Session title equals the SECOND known line (provider vs local
        // line stream may be one step apart): still not a track title.
        i = TrackInfo.resolve("奔跑", "羽·泉", "夜明けは近いyou may dream", "AIR - だけに",
                "暗い夜の", "夜明けは近いyou may dream");
        T.eq("line-as-title matches any known line", null, i.title);

        // 9. Blank strings are treated as absent, not shown as empty rows.
        i = TrackInfo.resolve("  ", "", "Song", "  ", null);
        T.eq("blank payload title falls to session", "Song", i.title);
        T.eq("blank payload artist falls to session", null, i.artist);
    }
}
