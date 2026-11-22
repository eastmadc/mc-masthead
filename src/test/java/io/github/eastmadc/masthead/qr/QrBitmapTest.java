package io.github.eastmadc.masthead.qr;

import io.github.eastmadc.masthead.qr.qrcodegen.QrCode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain-main test (no JUnit on this box). Renders sample URLs, checks size and quiet zone, and writes PNGs to the directory
 * given as args[0] so an external decoder (zxing-cpp) can confirm they scan:  java QrBitmapTest <outdir>
 */
public final class QrBitmapTest {
    static final String[] URLS = {
            "http://news.mc.example.org:8080/news/",
            "https://example.org/a/rather/long/path/to/the/newspaper/for/a/minecraft/server/that/likes/chickens/index.html?edition=2026-11-05&utm_source=map",
    };

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/qr-test");
        Files.createDirectories(out);
        for (int i = 0; i < URLS.length; i++) {
            boolean[] px = QrBitmap.render(URLS[i]);
            check(px.length == 128 * 128, "length");
            int modules = QrCode.encodeText(URLS[i], QrCode.Ecc.MEDIUM).size;
            int scale = 128 / (modules + 4);
            int margin = (128 - modules * scale) / 2;
            check(margin >= 2 * scale, "quiet zone >= 2 modules");
            for (int y = 0; y < 128; y++)
                for (int x = 0; x < 128; x++)
                    if (x < margin || y < margin || x >= 128 - margin || y >= 128 - margin)
                        check(!px[y * 128 + x], "quiet zone pixel dark at " + x + "," + y);
            // finder pattern corner must be dark
            check(px[margin * 128 + margin], "top-left finder");
            BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 128; y++)
                for (int x = 0; x < 128; x++) img.setRGB(x, y, px[y * 128 + x] ? 0x000000 : 0xFFFFFF);
            File f = out.resolve("qr-" + i + ".png").toFile();
            ImageIO.write(img, "png", f);
            System.out.println("ok " + f + " modules=" + modules + " scale=" + scale + " margin=" + margin + " url=" + URLS[i]);
        }
        try {
            QrBitmap.render("https://x/" + "a".repeat(2000));
            throw new AssertionError("expected too-long URL to fail");
        } catch (IllegalArgumentException expected) {
            System.out.println("ok too-long URL rejected");
        }
        System.out.println("QrBitmapTest PASS");
    }

    static void check(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }
}
