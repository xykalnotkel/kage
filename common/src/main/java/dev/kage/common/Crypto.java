package dev.kage.common;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small helpers used to authenticate the file transport. */
public final class Crypto {

    private Crypto() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4)
                    | Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    public static String hmac(String token, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes("UTF-8"), "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean verify(String token, String message, String signature) {
        if (token == null || signature == null) return false;
        String expect = hmac(token, message);
        if (expect.length() != signature.length()) return false;
        int diff = 0;
        for (int i = 0; i < expect.length(); i++) diff |= expect.charAt(i) ^ signature.charAt(i);
        return diff == 0;
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return hex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }
}
