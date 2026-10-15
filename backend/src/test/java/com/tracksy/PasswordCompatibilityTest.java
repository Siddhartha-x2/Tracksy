package com.tracksy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordCompatibilityTest {
    private final CompatiblePasswordEncoder encoder=new CompatiblePasswordEncoder();
    @Test void regularHashesVerifyOnlyTheCorrectPassword() {
        String hash=encoder.encode("TracksySecure2026!");
        assertTrue(encoder.matches("TracksySecure2026!",hash));
        assertFalse(encoder.matches("TracksySecure2026?",hash));
    }
    @Test void longLegacyPasswordsRetainTheirEntireIdentity() {
        String prefix="a".repeat(72),password=prefix+"-first";
        String hash=encoder.encode(password);
        assertTrue(hash.startsWith("{bcrypt-sha256}"));
        assertTrue(encoder.matches(password,hash));
        assertFalse(encoder.matches(prefix+"-second",hash));
        assertFalse(encoder.matches(prefix,hash));
    }
    @Test void utf8ByteLimitPreventsMultibyteTruncation() {
        String password="é".repeat(40);
        String hash=encoder.encode(password);
        assertTrue(hash.startsWith("{bcrypt-sha256}"));
        assertTrue(encoder.matches(password,hash));
        assertFalse(encoder.matches("é".repeat(39)+"à",hash));
    }
}
