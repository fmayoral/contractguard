package com.contractguard.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic SHA-256 hash of a plan's canonical content. Field values are
 * length-prefixed so no two distinct plans can produce the same canonical
 * byte stream through delimiter collisions.
 */
public final class PlanHasher {

    private PlanHasher() {
    }

    public static String hash(List<PlanItem> items) {
        MessageDigest digest = sha256();
        for (PlanItem item : items) {
            feed(digest, item.objective());
            item.expectedFiles().forEach(f -> feed(digest, f));
            feed(digest, item.proposedAction());
            item.testsToUpdate().forEach(t -> feed(digest, t));
            feed(digest, item.validationCommand());
            feed(digest, item.risk());
            feed(digest, item.rollback());
            item.evidenceIds().forEach(e -> feed(digest, e));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void feed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(intBytes(bytes.length));
        digest.update(bytes);
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
