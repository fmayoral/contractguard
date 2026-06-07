package com.contractguard.domain;

import java.util.UUID;

/** ID generation helpers shared across the domain. */
public final class Ids {

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** First UUID block; used for branch names like {@code contractguard/run-1a2b3c4d}. */
    public static String shortId(String id) {
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : id;
    }
}
