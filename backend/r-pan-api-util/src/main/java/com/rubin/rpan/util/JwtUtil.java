package com.rubin.rpan.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;

public final class JwtUtil {
  private static final String RENEWAL_TIME = "RENEWAL_TIME";
  private static final SecretKey SIGNING_KEY = signingKey();

  private JwtUtil() {}

  private static SecretKey signingKey() {
    String encoded = System.getenv("RPAN_JWT_SECRET");
    if (encoded == null || encoded.isBlank()) {
      return Jwts.SIG.HS256.key().build();
    }
    return Keys.hmacShaKeyFor(Decoders.BASE64.decode(encoded));
  }

  public static String generateToken(
      String subject, String claimKey, Object claimValue, Long expire) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .subject(subject)
        .claim(claimKey, claimValue)
        .claim(RENEWAL_TIME, new Date(now + expire / 2))
        .expiration(new Date(now + expire))
        .signWith(SIGNING_KEY, Jwts.SIG.HS256)
        .compact();
  }

  public static Object analyzeToken(String token, String claimKey) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      return Jwts.parser()
          .verifyWith(SIGNING_KEY)
          .build()
          .parseSignedClaims(token)
          .getPayload()
          .get(claimKey);
    } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
      return null;
    }
  }
}
