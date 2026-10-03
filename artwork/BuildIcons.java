import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Regenerates the shipped icons from the master artwork. Not part of the Gradle build; run it by hand:
 *
 *   java artwork/BuildIcons.java artwork/filesummer-master-1024.png \
 *        src/main/resources/icon packaging/FileSummer.ico
 *
 * Needs a JDK (ImageIO + AWT); the .ico is PNG-compressed, which Vista-and-later Explorer accepts.
 */
class BuildIcons {

    static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        BufferedImage src = ImageIO.read(Path.of(args[0]).toFile());
        Path iconDir = Path.of(args[1]);
        Path icoOut = Path.of(args[2]);
        Files.createDirectories(iconDir);
        Files.createDirectories(icoOut.getParent());

        List<Integer> sizes = new ArrayList<>();
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage img = rounded(scale(src, size));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            ImageIO.write(img, "png", iconDir.resolve("filesummer-" + size + ".png").toFile());
            sizes.add(size);
            pngs.add(png.toByteArray());
        }
        Files.write(icoOut, assembleIco(sizes, pngs));
        System.out.println("wrote " + iconDir + " (" + SIZES.length + " png) and " + icoOut);
    }

    private static BufferedImage scale(BufferedImage src, int target) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= target * 1.5) {
            cur = step(cur, Math.max(target, cur.getWidth() / 2));
        }
        return step(cur, target);
    }

    private static BufferedImage step(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static BufferedImage rounded(BufferedImage square) {
        int size = square.getWidth();
        float arc = Math.max(2f, size * 0.20f) * 2f;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, size, size);
        g.setComposite(AlphaComposite.Src);
        g.fill(new RoundRectangle2D.Float(0, 0, size, size, arc, arc));
        g.setComposite(AlphaComposite.SrcAtop);
        g.drawImage(square, 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] assembleIco(List<Integer> sizes, List<byte[]> pngs) throws IOException {
        int dirSize = 6 + pngs.size() * 16;
        ByteBuffer head = ByteBuffer.allocate(dirSize).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0);          // reserved
        head.putShort((short) 1);          // type: icon
        head.putShort((short) pngs.size());
        int offset = dirSize;
        for (int i = 0; i < pngs.size(); i++) {
            int size = sizes.get(i);
            head.put((byte) (size >= 256 ? 0 : size));   // 0 == 256
            head.put((byte) (size >= 256 ? 0 : size));
            head.put((byte) 0);            // palette colours
            head.put((byte) 0);            // reserved
            head.putShort((short) 1);      // planes
            head.putShort((short) 32);     // bpp
            head.putInt(pngs.get(i).length);
            head.putInt(offset);
            offset += pngs.get(i).length;
        }
        ByteArrayOutputStream ico = new ByteArrayOutputStream(offset);
        ico.write(head.array());
        for (byte[] png : pngs) {
            ico.write(png);
        }
        return ico.toByteArray();
    }
}
