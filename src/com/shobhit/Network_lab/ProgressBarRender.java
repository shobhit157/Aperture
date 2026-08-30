package com.shobhit.Network_lab;

public class ProgressBarRender {
	
	public static void render(String direction, String filename, int pct, String done, String total) {
        int filled = pct / 5;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? "=" : " ");
        }
        System.out.print("\r" + direction + " " + filename +
                " [" + bar + "] " + pct + "% (" + done + "/" + total + " bytes)");
        System.out.flush();
        if (pct >= 100) {
            System.out.println();
        }
    }

}
