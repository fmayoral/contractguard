package com.contractguard.adapter.security;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for remote-repository credentials at rest
 * (ADR-0007). Keyed by an operator-supplied master key; never fails at
 * startup so local-only deployments need no configuration, only when a
 * credential is actually encrypted or decrypted.
 */
public class AesGcmCredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;

    private final String base64Key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCredentialCipher(String base64Key) {
        this.base64Key = base64Key;
    }

    public record EncryptedValue(String ciphertextBase64, String nonceBase64) {
    }

    public EncryptedValue encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("credential encryption failed", e);
        }
    }

    public String decrypt(EncryptedValue value) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] nonce = Base64.getDecoder().decode(value.nonceBase64());
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(value.ciphertextBase64()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw ContractGuardException.of(FailureCategory.CREDENTIAL_KEY_NOT_CONFIGURED,
                    "stored credential failed authentication during decryption",
                    "The credential is corrupt, or CONTRACTGUARD_CREDENTIAL_KEY changed since it was stored; "
                            + "re-register the repository.");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("credential decryption failed", e);
        }
    }

    private SecretKey key() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        } catch (IllegalArgumentException e) {
            keyBytes = new byte[0];
        }
        if (keyBytes.length != KEY_BYTES) {
            throw ContractGuardException.of(FailureCategory.CREDENTIAL_KEY_NOT_CONFIGURED,
                    "CONTRACTGUARD_CREDENTIAL_KEY is not configured with a 32-byte base64 key",
                    "Set CONTRACTGUARD_CREDENTIAL_KEY to a base64-encoded 256-bit key "
                            + "(e.g. openssl rand -base64 32) before registering remote repositories.");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
