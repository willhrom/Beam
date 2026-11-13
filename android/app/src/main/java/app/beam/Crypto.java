package app.beam;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/** Plain-Java crypto helpers shared with the web app's format (no Android APIs, so it can be unit-tested). */
public final class Crypto {
  private Crypto() {}

  public static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
  public static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }

  public static byte[] sha256(byte[] b) {
    try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new RuntimeException(e); }
  }

  public static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x & 0xff));
    return sb.toString();
  }

  /** Device ID = first 16 bytes of SHA-256 of the SPKI public key, in hex. */
  public static String idFromPub(String pubB64) {
    try { return hex(sha256(unb64(pubB64))).substring(0, 32); } catch (Exception e) { return ""; }
  }

  public static String randomId() {
    byte[] b = new byte[8];
    new SecureRandom().nextBytes(b);
    return hex(b);
  }

  public static String shortId(String id) {
    return (id.substring(0, 4) + "-" + id.substring(4, 8)).toUpperCase();
  }

  /** Six digits both devices show while pairing (same formula as the web app). */
  public static String safetyCode(String pubA, String pubB) {
    String x = pubA.compareTo(pubB) <= 0 ? pubA : pubB;
    String y = pubA.compareTo(pubB) <= 0 ? pubB : pubA;
    byte[] h = sha256((x + "|" + y).getBytes(StandardCharsets.UTF_8));
    long n = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
    String s = String.format("%06d", n % 1000000);
    return s.substring(0, 3) + " " + s.substring(3);
  }

  /** Verify a raw (r||s, 64 byte) P-256 signature, as WebCrypto produces. */
  public static boolean verify(String pubB64, String sigB64, String text) {
    try {
      PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(pubB64)));
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initVerify(key);
      s.update(text.getBytes(StandardCharsets.UTF_8));
      return s.verify(rawToDer(unb64(sigB64)));
    } catch (Exception e) {
      return false;
    }
  }

  // ---- signature format conversion (Java uses DER, WebCrypto uses raw r||s) ----
  public static byte[] derToRaw(byte[] der) {
    int i = 2;
    if ((der[1] & 0x80) != 0) i = 2 + (der[1] & 0x7f);
    if (der[i] != 0x02) throw new IllegalArgumentException("bad der");
    int rLen = der[i + 1];
    byte[] r = Arrays.copyOfRange(der, i + 2, i + 2 + rLen);
    i = i + 2 + rLen;
    if (der[i] != 0x02) throw new IllegalArgumentException("bad der");
    int sLen = der[i + 1];
    byte[] s = Arrays.copyOfRange(der, i + 2, i + 2 + sLen);
    byte[] out = new byte[64];
    put32(r, out, 0);
    put32(s, out, 32);
    return out;
  }

  private static void put32(byte[] v, byte[] out, int off) {
    int start = 0;
    while (start < v.length - 1 && v[start] == 0) start++;
    int len = v.length - start;
    if (len > 32) throw new IllegalArgumentException("bad int");
    System.arraycopy(v, start, out, off + 32 - len, len);
  }

  public static byte[] rawToDer(byte[] raw) {
    if (raw.length != 64) throw new IllegalArgumentException("bad sig");
    byte[] r = derInt(Arrays.copyOfRange(raw, 0, 32));
    byte[] s = derInt(Arrays.copyOfRange(raw, 32, 64));
    byte[] out = new byte[2 + r.length + s.length];
    out[0] = 0x30;
    out[1] = (byte) (r.length + s.length);
    System.arraycopy(r, 0, out, 2, r.length);
    System.arraycopy(s, 0, out, 2 + r.length, s.length);
    return out;
  }

  private static byte[] derInt(byte[] v) {
    int start = 0;
    while (start < v.length - 1 && v[start] == 0) start++;
    boolean pad = (v[start] & 0x80) != 0;
    int len = v.length - start + (pad ? 1 : 0);
    byte[] out = new byte[2 + len];
    out[0] = 0x02;
    out[1] = (byte) len;
    System.arraycopy(v, start, out, 2 + (pad ? 1 : 0), v.length - start);
    return out;
  }
}
