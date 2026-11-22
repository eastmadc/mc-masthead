package io.github.eastmadc.masthead.qr;

import io.github.eastmadc.masthead.qr.qrcodegen.QrCode;

/** Renders a URL as a 128x128 monochrome bitmap (the size of a Minecraft map). Pure Java, no Bukkit. */
public final class QrBitmap {
    public static final int SIZE = 128;
    /** Light modules required around the code on every side (the QR spec asks for 4; scanners cope with 2). */
    public static final int MIN_QUIET = 2;

    private QrBitmap() {}

    /**
     * Encodes {@code url} with at least ECC level MEDIUM (the library raises it when that costs no extra size) and returns a
     * row-major {@code boolean[128*128]}: index {@code y*128+x}, {@code true} = dark. Each module is drawn as an integer
     * square of pixels as large as fits with a quiet zone of at least {@link #MIN_QUIET} modules, centred.
     *
     * @throws IllegalArgumentException if the URL is too long to fit at one pixel per module
     */
    public static boolean[] render(String url) {
        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
        int n = qr.size;
        int scale = SIZE / (n + 2 * MIN_QUIET);
        if (scale < 1) throw new IllegalArgumentException("URL too long for a " + SIZE + "px QR code (" + n + " modules)");
        int offset = (SIZE - n * scale) / 2;
        boolean[] px = new boolean[SIZE * SIZE];
        for (int my = 0; my < n; my++)
            for (int mx = 0; mx < n; mx++) {
                if (!qr.getModule(mx, my)) continue;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (offset + my * scale + dy) * SIZE + offset + mx * scale;
                    for (int dx = 0; dx < scale; dx++) px[row + dx] = true;
                }
            }
        return px;
    }
}
