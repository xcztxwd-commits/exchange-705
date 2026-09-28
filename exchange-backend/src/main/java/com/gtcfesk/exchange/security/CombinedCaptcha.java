package com.gtcfesk.exchange.security;

import cn.hutool.captcha.AbstractCaptcha;
import cn.hutool.captcha.generator.CodeGenerator;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;

/** Hutool CAPTCHA with bounded, independently randomized interference. */
public class CombinedCaptcha extends AbstractCaptcha {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    int lines, circles, warps;

    public CombinedCaptcha() {
        super(240, 104, 4, 0);
        setGenerator(new CodeGenerator() {
            public String generate() {
                String result;
                do {
                    StringBuilder code = new StringBuilder();
                    for (int i = 0; i < 4; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
                    result = code.toString();
                } while (!result.matches(".*[A-Z].*") || !result.matches(".*[0-9].*"));
                return result;
            }
            public boolean verify(String code, String input) {
                return code != null && input != null && code.equalsIgnoreCase(input.trim());
            }
        });
    }

    protected Image createImage(String code) {
        lines = 3 + RANDOM.nextInt(7);
        circles = 1 + RANDOM.nextInt(5);
        warps = RANDOM.nextInt(2);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(246, 248, 239));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font("SansSerif", Font.BOLD, 60));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(45 + RANDOM.nextInt(65), 60 + RANDOM.nextInt(65), 35 + RANDOM.nextInt(65)));
            g.drawString(code.substring(i, i + 1), 24 + i * 49, 73 + RANDOM.nextInt(7) - 3);
        }
        g.setStroke(new BasicStroke(1.8f));
        for (int i = 0; i < lines; i++) {
            g.setColor(new Color(95 + RANDOM.nextInt(65), 110 + RANDOM.nextInt(55), 70 + RANDOM.nextInt(65)));
            g.drawLine(8 + RANDOM.nextInt(65), 12 + RANDOM.nextInt(80), 165 + RANDOM.nextInt(65), 12 + RANDOM.nextInt(80));
        }
        for (int i = 0; i < circles; i++) {
            int diameter = 16 + RANDOM.nextInt(29);
            g.setColor(new Color(115 + RANDOM.nextInt(55), 130 + RANDOM.nextInt(50), 85 + RANDOM.nextInt(65)));
            g.drawOval(5 + RANDOM.nextInt(width - diameter - 10), 5 + RANDOM.nextInt(height - diameter - 10), diameter, diameter);
        }
        g.dispose();

        // 0–1 sine displacement layers, not ShearCaptcha's line-width parameter.
        // Sum their offsets before resampling once to preserve small-screen readability.
        double[] phases = new double[warps], periods = new double[warps], amplitudes = new double[warps];
        for (int i = 0; i < warps; i++) {
            phases[i] = RANDOM.nextDouble() * Math.PI * 2;
            periods[i] = 65 + RANDOM.nextInt(65);
            amplitudes[i] = 1.5 + RANDOM.nextDouble() * 1.5;
        }
        BufferedImage distorted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double dx = 0, dy = 0;
            for (int i = 0; i < warps; i++) {
                if (i % 2 == 0) dx += amplitudes[i] * Math.sin(y * Math.PI * 2 / periods[i] + phases[i]);
                else dy += amplitudes[i] * Math.sin(x * Math.PI * 2 / periods[i] + phases[i]);
            }
            int sx = (int)Math.round(x + dx), sy = (int)Math.round(y + dy);
            distorted.setRGB(x, y, sx >= 0 && sx < width && sy >= 0 && sy < height
                ? image.getRGB(sx, sy) : new Color(246, 248, 239).getRGB());
        }
        return distorted;
    }

}
