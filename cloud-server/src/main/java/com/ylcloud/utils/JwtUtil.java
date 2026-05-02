package com.ylcloud.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;

public class JwtUtil {
    // 密钥
    private static final String SECRET = "yl-orb-secret-key-ylorb-setcret-key";
    private static final long EXPIRATION = 1000 * 60 * 60;

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());


    /*
        创建 jwt token
     */
    public String createToken(String username,String role) {
        return Jwts.builder()
                .setSubject(username)  // 主题
                .claim("role",role) // 自定义声明
                .setIssuedAt(new Date()) // 签发时间
                .setExpiration(new Date(System.currentTimeMillis() +  EXPIRATION)) // 过期时间
                .signWith(KEY,SignatureAlgorithm.HS256)  // 使用密钥签名
                .compact(); // 生成紧凑的 jwt 字符串
    }

    /**
     * 解析 token
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(KEY)                      // 设置签名密钥
                .build()
                .parseClaimsJws(token)                   // 解析 JWT
                .getBody();                              // 获取声明内容
    }

    /**
     * 验证 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(KEY)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 Token 中获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

}
