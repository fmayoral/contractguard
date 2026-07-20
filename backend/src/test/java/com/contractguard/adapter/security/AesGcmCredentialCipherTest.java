package com.contractguard.adapter.security;

import com.contractguard.adapter.security.AesGcmCredentialCipher.EncryptedValue;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsPlaintextThroughEncryptAndDecrypt() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(KEY);

        EncryptedValue encrypted = cipher.encrypt("gh-token-abc123");

        assertThat(cipher.decrypt(encrypted)).isEqualTo("gh-token-abc123");
        assertThat(encrypted.ciphertextBase64()).doesNotContain("gh-token-abc123");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(KEY);

        EncryptedValue first = cipher.encrypt("gh-token-abc123");
        EncryptedValue second = cipher.encrypt("gh-token-abc123");

        assertThat(first.nonceBase64()).isNotEqualTo(second.nonceBase64());
        assertThat(first.ciphertextBase64()).isNotEqualTo(second.ciphertextBase64());
    }

    @Test
    void tamperedCiphertextFailsAuthenticationOnDecrypt() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(KEY);
        EncryptedValue encrypted = cipher.encrypt("gh-token-abc123");
        byte[] tampered = Base64.getDecoder().decode(encrypted.ciphertextBase64());
        tampered[0] ^= 0x01;
        EncryptedValue corrupted = new EncryptedValue(
                Base64.getEncoder().encodeToString(tampered), encrypted.nonceBase64());

        assertThatThrownBy(() -> cipher.decrypt(corrupted))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.CREDENTIAL_KEY_NOT_CONFIGURED));
    }

    @Test
    void missingMasterKeyFailsClearlyOnEncrypt() {
        AesGcmCredentialCipher cipher = new AesGcmCredentialCipher("");

        assertThatThrownBy(() -> cipher.encrypt("gh-token-abc123"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.CREDENTIAL_KEY_NOT_CONFIGURED));
    }
}
