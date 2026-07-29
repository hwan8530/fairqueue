package com.example.eventplatform.security;

import com.example.eventplatform.users.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private final long EXP_ACCESS_TOKEN_VALIDITY_TIME;
  private final long EXP_REFRESH_TOKEN_VALIDITY_TIME;
  private final Key TOKEN_SECRET;
  private final Key ENTRY_TOKEN_SECRET;

  public JwtUtil(@Value("${jwt.access_token.valid_time}") String accessTokenTime,
      @Value("${jwt.refresh_token.valid_time}") String refreshTokenTime,
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.entry_token_secret}") String entryTokenSecret) {
    this.EXP_ACCESS_TOKEN_VALIDITY_TIME = Long.parseLong(accessTokenTime);
    this.EXP_REFRESH_TOKEN_VALIDITY_TIME = Long.parseLong(refreshTokenTime);
    this.TOKEN_SECRET = Keys.hmacShaKeyFor(secret.getBytes());
    this.ENTRY_TOKEN_SECRET = Keys.hmacShaKeyFor(entryTokenSecret.getBytes());
  }

  public long accessTokenExpiresIn() {
    return this.EXP_ACCESS_TOKEN_VALIDITY_TIME / 1000; // 초 단위로 리턴
  }

  public long refreshTokenExpiresIn() {
    return this.EXP_REFRESH_TOKEN_VALIDITY_TIME / 1000;
  }

  // role을 claim에 함께 실어야 getAuthentication()에서 DB 재조회 없이 권한(ROLE_*)을 복원할 수 있다.
  public String makeAccessToken(String username, Role role) {
    return Jwts.builder().subject("Access Token").issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + EXP_ACCESS_TOKEN_VALIDITY_TIME))
        .claim("username", username).claim("role", role.name()).signWith(TOKEN_SECRET).compact();
  }

  public String makeRefreshToken(String username) {
    return Jwts.builder().subject("Refresh Token").issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + EXP_REFRESH_TOKEN_VALIDITY_TIME))
        .claim("username", username).signWith(TOKEN_SECRET).compact();
  }

  // ttl은 초 단위로 받는데(호출부는 Duration.ofSeconds(ttl)로 Redis TTL을 건다), 여기서
  // now.getTime()(밀리초)에 그대로 더하면 만료 시각이 사실상 즉시(ttl밀리초 뒤)가 돼버린다 -
  // Redis 키의 TTL(초)과 JWT 내부 exp 클레임(밀리초 오해)이 서로 다른 단위로 계산되고 있었다.
  public String makeEntryToken(String username, Date now, long ttl) {
    return Jwts.builder().subject("Entry Token").issuedAt(now)
        .expiration(new Date(now.getTime() + ttl * 1000)).claim("username", username)
        .claim("ttl", ttl).signWith(ENTRY_TOKEN_SECRET).compact();
  }

  public String extractNameFromToken(String token) {
    return parseClaims(token).get("username", String.class);
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith((SecretKey) TOKEN_SECRET).build().parseSignedClaims(token)
        .getPayload();
  }

  /*
   * 토큰에서 username/role을 꺼내 인증 완료된 Authentication을 만든다.
   * UsernamePasswordAuthenticationToken은 생성자가 2개인데, (principal, credentials) 2-인자
   * 생성자는 "아직 인증 전"이라는 의미로 authenticated=false, authorities=[]를 강제로 세팅한다
   * (AuthenticationManager가 이후 검증해서 3-인자 생성자로 다시 만들어주는 것을 전제로 한 설계).
   * 여기서는 서명 검증(parseSignedClaims)이 이미 인증을 완료한 셈이므로, authorities까지 채운
   * 3-인자 생성자를 반드시 써야 authenticated=true가 되고 hasRole()/authenticated() 판정이
   * 의도대로 동작한다. (이 차이를 놓치면 유효한 토큰으로도 모든 보호된 API가 거부된다.)
   */
  public Authentication getAuthentication(String token) {
    // token이 유효한지 확인
    // 유효하지 않다면 claim에서 추출하려고 할 때 throw 된다.
    try {
      Claims claims = parseClaims(token);
      String username = claims.get("username", String.class);
      String role = claims.get("role", String.class);
      List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
      return new UsernamePasswordAuthenticationToken(username, token, authorities);
    } catch (Exception e) {
      // 만료 체크가 아닌 다른 에러는 발생하면 에러 처리
      throw new JwtException("Invalid JwtToken");
    }
  }
}
