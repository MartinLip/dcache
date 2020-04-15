package org.dcache.auth;

import java.io.Serializable;

import static java.util.Objects.requireNonNull;

public class OpenIdClientSecret implements Serializable
{
    private final String id;
    private final String secret;

    public OpenIdClientSecret(String id, String secret) {
        this.id = requireNonNull(id);
        this.secret = requireNonNull(secret);
    }

    public String getId() {
        return id;
    }

    public String getSecret() {
        return secret;
    }
}
