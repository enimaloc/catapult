package fr.enimaloc.catapult.assets;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarImagesTest {

    /** A {@code width}x{@code height} PNG: red on its left half, blue on its right one. */
    static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) image.setRGB(x, y, (x < width / 2 ? Color.RED : Color.BLUE).getRGB());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aPicture_becomesASmallWebpSquareOfTheSizeAsked() throws IOException {
        byte[] source = png(300, 300);

        byte[] webp = AvatarImages.webp(source, 64);

        assertThat(new String(webp, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(webp, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        assertThat(webp.length).isLessThan(source.length);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(webp));
        assertThat(back.getWidth()).isEqualTo(64);
        assertThat(back.getHeight()).isEqualTo(64);
    }

    @Test
    void aWidePicture_keepsItsCentre() throws IOException {
        // 400x100: the centre 100x100 square straddles the red/blue edge.
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(AvatarImages.webp(png(400, 100), 64)));

        assertThat(new Color(back.getRGB(8, 32)).getRed()).isGreaterThan(200);
        assertThat(new Color(back.getRGB(56, 32)).getBlue()).isGreaterThan(200);
    }

    @Test
    void whatIsNoPicture_orIsTooLarge_isRefused() throws IOException {
        assertThat(AvatarImages.webp("<html>".getBytes(StandardCharsets.UTF_8), 64)).isNull();
        assertThat(AvatarImages.webp(png(AvatarImages.MAX_SIDE + 1, 1), 64)).isNull();
    }
}
