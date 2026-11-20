package cn.qxfly.aod;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import static cn.qxfly.aod.AnchorIds.ANCHOR_ORDER;
import static cn.qxfly.aod.AnchorIds.canonicalCandidates;
import static cn.qxfly.aod.AodOverlayController.reporter;
import static cn.qxfly.aod.AodOverlayController.reporter;

/**
 * Enumerates the AOD component tree for the anchor picker: entry name, class
 * and absolute bounds of every view that can serve as a lyric anchor.
 *
 * <p>Extracted from {@link AodOverlayController} (it was 3000+ lines); the
 * logic is read-only view traversal and does not touch overlay state.
 */
public final class ComponentCollector {

    private ComponentCollector() {
    }

    /**
     * Collects anchor candidates for the app's anchor picker, as
     * "id|class|[absX,absY w h]" lines - exactly the 4 coarse regions
     * (clock / image / timedate / notify). The raw traversal below is only
     * the discovery pass; its fine-grained entries are mapped onto the 4
     * regions and never reach the provider, so the dropdown stays short and
     * every entry is guaranteed meaningful for insertion.
     */
    static void collectComponents(@NonNull ViewGroup parent,
                                  @NonNull List<String> out, int depth, int absX, int absY) {
        List<String> raw = new java.util.ArrayList<>(24);
        collectRawComponents(parent, raw, 0, 0, 0);
        for (String canonical : ANCHOR_ORDER) {
            String[] cands = canonicalCandidates(canonical);
            if (cands == null) {
                continue;
            }
            for (String cand : cands) {
                String line = findById(raw, cand);
                if (line != null) {
                    // Replace the fine-grained name with the canonical one but
                    // keep the discovered class and absolute bounds.
                    String rest = line.substring(cand.length() + 1);
                    out.add(canonical + rest);
                    break;
                }
            }
        }
        AodLyricModule.i("anchor regions reported: " + out);
    }

    /**
     * Raw discovery pass (kept from the previous fine-grained picker): visible,
     * measured, named views in ABSOLUTE window coordinates, deduped by name
     * and by absolute position. Internal only - see {@link #collectComponents}.
     */
    static void collectRawComponents(@NonNull ViewGroup parent,
                                     @NonNull List<String> out, int depth, int absX, int absY) {
        if (depth > 8 || out.size() >= 60) {
            return;
        }
        int count = parent.getChildCount();
        for (int i = 0; i < count && out.size() < 60; i++) {
            View c = parent.getChildAt(i);
            if (c == null || c instanceof LyricTextView) {
                continue;
            }
            try {
                String name = null;
                if (c.getId() != View.NO_ID) {
                    name = c.getResources().getResourceEntryName(c.getId());
                }
                boolean useful = c.getVisibility() == View.VISIBLE
                        && c.getWidth() > 0 && c.getHeight() > 0
                        && name != null && !name.isEmpty()
                        && !(c instanceof android.view.ViewStub);
                if (useful) {
                    String bounds = "[" + (absX + c.getLeft()) + "," + (absY + c.getTop())
                            + " " + c.getWidth() + "x" + c.getHeight() + "]";
                    if (!containsId(out, name) && !containsBounds(out, bounds)) {
                        out.add(name + "|"
                                + c.getClass().getSimpleName()
                                + "|" + bounds);
                    }
                }
                if (c instanceof ViewGroup) {
                    collectRawComponents((ViewGroup) c, out, depth + 1,
                            absX + c.getLeft(), absY + c.getTop());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** True when any collected line ends with the given absolute-bounds string. */
    static boolean containsBounds(@NonNull List<String> lines, @NonNull String bounds) {
        for (String line : lines) {
            if (line.endsWith("|" + bounds)) {
                return true;
            }
        }
        return false;
    }

    /** True when any collected line starts with "name|". */
    static boolean containsId(@NonNull List<String> lines, @NonNull String name) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(name + "|")) {
                return true;
            }
        }
        return false;
    }

    /** First raw line whose id equals the given entry name, or null. */
    @Nullable
    static String findById(@NonNull List<String> raw, @NonNull String id) {
        String prefix = id + "|";
        for (String line : raw) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }
}
