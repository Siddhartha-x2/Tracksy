package com.tracksy;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Keeps legacy long passwords usable without BCrypt's 72-byte truncation. */
public final class CompatiblePasswordEncoder implements PasswordEncoder {
    private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder(11);
    private static final String PREHASH="{bcrypt-sha256}";
    private String digest(CharSequence password) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(password.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public String encode(CharSequence raw) { return raw.toString().getBytes(StandardCharsets.UTF_8).length>72?PREHASH+bcrypt.encode(digest(raw)):bcrypt.encode(raw); }
    @Override public boolean matches(CharSequence raw,String encoded) {
        if(encoded.startsWith(PREHASH)) return bcrypt.matches(digest(raw),encoded.substring(PREHASH.length()));
        return raw.toString().getBytes(StandardCharsets.UTF_8).length<=72 && bcrypt.matches(raw,encoded);
    }
}
