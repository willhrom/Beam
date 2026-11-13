package app.beam;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** This device's signing key. The private half lives in the Android Keystore and never leaves it. */
final class Identity {
  private static final String ALIAS = "beam-identity";
  final String pub;
  final String id;
  private final PrivateKey priv;

  Identity() {
    try {
      KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
      ks.load(null);
      if (!ks.containsAlias(ALIAS)) {
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build());
        g.generateKeyPair();
      }
      priv = (PrivateKey) ks.getKey(ALIAS, null);
      pub = Crypto.b64(ks.getCertificate(ALIAS).getPublicKey().getEncoded());
      id = Crypto.idFromPub(pub);
    } catch (Exception e) {
      throw new RuntimeException("Couldn't set up the device key", e);
    }
  }

  /** Signs text and returns a raw (r||s) signature in base64, the format WebCrypto uses. */
  String sign(String text) {
    try {
      Signature s = Signature.getInstance("SHA256withECDSA");
      s.initSign(priv);
      s.update(text.getBytes(StandardCharsets.UTF_8));
      return Crypto.b64(Crypto.derToRaw(s.sign()));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
