package com.example.eventplatform.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private final long EXP_ACCESS_TOKEN_VALIDITY_TIME;
  private final long EXP_REFRESH_TOKEN_VALIDITY_TIME;
  private final Key TOKEN_SECRET;
  private final Key ENTRY_TOKEN_SECRET;

  public JwtUtil(@Value("{$jwt.access_token.valid_time}") long acessTokenTime,
      @Value("{$.jwt.refresh_token.valid_time}") long refreshTokenTime,
      @Value("{$.jwt.secret}") String secret,
      @Value("{$.jwt.entry_token_secret}") String entryTokenSecret) {
    this.EXP_ACCESS_TOKEN_VALIDITY_TIME = acessTokenTime;
    this.EXP_REFRESH_TOKEN_VALIDITY_TIME = refreshTokenTime;
    this.TOKEN_SECRET = Keys.hmacShaKeyFor(secret.getBytes());
    this.ENTRY_TOKEN_SECRET = Keys.hmacShaKeyFor(entryTokenSecret.getBytes());
  }

  public long accessTokenExpiresIn() {
    return this.EXP_ACCESS_TOKEN_VALIDITY_TIME / 1000; // 초 단위로 리턴
  }

  public long refreshTokenExpiresIn() {
    return this.EXP_REFRESH_TOKEN_VALIDITY_TIME / 1000;
  }

  public String makeAccessToken(String username) {
    return Jwts.builder().subject("Access Token").issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + EXP_ACCESS_TOKEN_VALIDITY_TIME))
        .claim("username", username).signWith(TOKEN_SECRET).compact();
  }

  public String makeRefreshToken(String username) {
    return Jwts.builder().subject("Refresh Token").issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + EXP_REFRESH_TOKEN_VALIDITY_TIME))
        .claim("username", username).signWith(TOKEN_SECRET).compact();
  }

  public String makeEntryToken(String username, Date now, long ttl) {
    return Jwts.builder().subject("Entry Token").issuedAt(now)
        .expiration(new Date(now.getTime() + ttl)).claim("username", username)
        .claim("ttl", ttl).signWith(ENTRY_TOKEN_SECRET).compact();
  }

  public String extractNameFromToken(String token) {
    return Jwts.parser().verifyWith((SecretKey) TOKEN_SECRET).build().parseSignedClaims(token)
        .getPayload().get("username", String.class);
  }

  public Authentication getAuthentication(String token) {
    // token이 유효한지 확인
    // 유효하지 않다면 claim에서 추출하려고 할 때 throw 된다.
    try {
      String nameFromAccessToken = extractNameFromToken(token);
      return new UsernamePasswordAuthenticationToken(nameFromAccessToken, token);
    } catch (Exception e) {
      // 만료 체크가 아닌 다른 에러는 발생하면 에러 처리
      throw new JwtException("Invalid JwtToken");
    }
  }
}
