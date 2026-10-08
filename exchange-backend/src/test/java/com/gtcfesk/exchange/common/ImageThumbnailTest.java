package com.gtcfesk.exchange.common;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ImageThumbnailTest {
 @Test void largeImagesAreSampledWithoutChangingOriginalBytes()throws Exception {
  BufferedImage original=new BufferedImage(4032,3024,BufferedImage.TYPE_INT_RGB);
  original.setRGB(0,0,0xff123456);
  ByteArrayOutputStream encoded=new ByteArrayOutputStream();assertTrue(ImageIO.write(original,"png",encoded));original.flush();
  byte[] bytes=encoded.toByteArray(),copy=bytes.clone();
  byte[] thumbnail=ImageFiles.thumbnail(new ByteArrayInputStream(bytes));
  BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(thumbnail));
  assertTrue(decoded.getWidth()<=320&&decoded.getHeight()<=320);assertTrue(thumbnail.length<bytes.length);
  assertEquals(0xff123456,decoded.getRGB(0,0));assertArrayEquals(copy,bytes);
  assertThrows(BusinessException.class,()->ImageFiles.thumbnail(new ByteArrayInputStream("not an image".getBytes("UTF-8"))));
 }
}
