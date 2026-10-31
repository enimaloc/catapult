package fr.enimaloc.catapult.assets;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * An avatar as the pages show it: the picture's centre square, scaled to the size asked, as a lossy WebP (libwebp,
 * through webp-imageio). A 300x300 Twitch PNG of tens of KB becomes a few KB.
 */
final class AvatarImages {
    static final float QUALITY = 0.8f;
    /** Beyond this, a picture is refused before it is decoded: an avatar is never that large. */
    static final int MAX_SIDE = 4096;

    private AvatarImages() {
    }

    /** {@code source} (PNG, JPEG...) as a {@code size}-pixel square WebP; null when it is no picture. */
    static byte[] webp(byte[] source, int size) {
        try {
            BufferedImage picture = decode(source);
            return picture == null ? null : encode(square(picture, size));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The picture, its dimensions read first: null when no reader knows it, or when it is over MAX_SIDE. */
    private static BufferedImage decode(byte[] source) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage square(BufferedImage picture, int size) {
        int side = Math.min(picture.getWidth(), picture.getHeight());
        int x = (picture.getWidth() - side) / 2;
        int y = (picture.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(picture, 0, 0, size, size, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) throw new IOException("no WebP encoder");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossy");
        param.setCompressionQuality(QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
