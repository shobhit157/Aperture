package com.shobhit.Network_lab;

public class ProgressBarRender {

    public static void render(String direction, String filename, int pct, String done, String total) {
        int filled = pct / 5;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? "=" : " ");
        }
        // Shortened: dropped the raw byte counts (done/total). The full
        // line, including byte counts, could reach 70-75 characters — wide
        // enough to wrap on many terminal widths. Once wrapped, \r only
        // returns to the start of the CURRENT visual row, not the true
        // start of the line, so each redraw left the pre-wrap portion of
        // the previous line untouched — visually indistinguishable from a
        // stack of new lines appearing, even though it was one \r-joined
        // line the whole time. This shortened line stays under ~50
        // characters, comfortably under any reasonable terminal width.
        System.out.print("\r" + direction + " " + filename + " [" + bar + "] " + pct + "%   ");
        System.out.flush();
        if (pct >= 100) {
            System.out.println();
        }
    }

}
