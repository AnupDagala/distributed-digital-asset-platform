package dev.assetplatform;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;

public final class TestFiles {
  private TestFiles() {}

  public static byte[] image(String format, int width, int height) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
    return out.toByteArray();
  }

  public static byte[] pdf() throws IOException {
    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.addPage(new PDPage());
      doc.save(out);
      return out.toByteArray();
    }
  }
}
