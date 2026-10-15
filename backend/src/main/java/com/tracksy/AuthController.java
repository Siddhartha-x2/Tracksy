package com.tracksy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.bouncycastle.crypto.generators.SCrypt;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final Db db; private final Access access; private final PasswordEncoder passwords;
    private final UserDetailsService users; private final SecurityContextRepository contexts; private final Environment environment;
    private final Map<String,long[]> attempts=new ConcurrentHashMap<>();
    private final String dummyHash;
    private volatile boolean ready;
    public AuthController(Db db,Access access,PasswordEncoder passwords,UserDetailsService users,SecurityContextRepository contexts,Environment environment) {
        this.db=db; this.access=access; this.passwords=passwords; this.users=users; this.contexts=contexts; this.environment=environment;
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @EventListener(ApplicationReadyEvent.class) public void markReady() { ready=true; }
    @GetMapping("/health") ResponseEntity<Map<String,Object>> health() {
        if(!ready) return ResponseEntity.status(503).body(Map.of("status","starting"));
        try { db.count("SELECT 1"); }
        catch(DataAccessException error) { return ResponseEntity.status(503).body(Map.of("status","unavailable")); }
        return ResponseEntity.ok(Map.of("status","ok","app","Tracksy","stack","Spring Boot / PostgreSQL"));
    }
    @GetMapping("/auth/config") Map<String,Object> config() {
        boolean demo=Arrays.asList(environment.getActiveProfiles()).contains("demo");
        return demo ? Map.of("demoEnabled",true,"demoAccounts",DemoData.accounts()) : Map.of("demoEnabled",false);
    }
    @GetMapping("/auth/csrf") Map<String,Object> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/auth/me") Map<String,Object> me(CsrfToken token) { return Map.of("user",access.user(),"csrfToken",token.getToken()); }
    private void limited(HttpServletRequest req) {
        long now=System.currentTimeMillis();
        synchronized(attempts) {
            attempts.entrySet().removeIf(e -> now-e.getValue()[0]>900000);
            if(attempts.size()>10000) throw new ApiException(429,"Too many sign-in attempts. Try again later.");
            long[] bucket=attempts.computeIfAbsent(req.getRemoteAddr(),key -> new long[]{now,0});
            if(++bucket[1]>25) throw new ApiException(429,"Too many sign-in attempts. Try again in 15 minutes.");
        }
    }
    private String password(Map<String,Object> body,boolean creating) {
        Object raw=body.get("password");
        if(!(raw instanceof String p) || p.getBytes(StandardCharsets.UTF_8).length>(creating?72:512) || p.length()>128 || p.length()<(creating?12:1))
            throw new ApiException(400,creating?"Use a password with at least 12 characters and at most 72 UTF-8 bytes.":"Enter a valid password.");
        return p;
    }
    private Map<String,Object> signedIn(String email,HttpServletRequest req,HttpServletResponse res) {
        if(req.getSession(false)!=null) req.changeSessionId();
        var principal=users.loadUserByUsername(email);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        SecurityContextHolder.setContext(context); contexts.saveContext(context,req,res);
        var tokens=new HttpSessionCsrfTokenRepository(); var newToken=tokens.generateToken(req); tokens.saveToken(newToken,req,res);
        return Map.of("user",access.user(),"csrfToken",newToken.getToken());
    }
    @PostMapping("/auth/login") Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res) {
        limited(req); String email=Input.email(body,"email"), password=password(body,false);
        var rows=db.query("SELECT password_hash,enabled FROM users WHERE email=?",email);
        String hash=rows.isEmpty()?dummyHash:(String)rows.getFirst().get("password_hash");
        boolean legacy=hash.matches("[0-9a-f]{32}:[0-9a-f]{128}");
        boolean valid=legacy ? MessageDigest.isEqual(SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),hash.substring(0,32).getBytes(StandardCharsets.UTF_8),16384,8,1,64),HexFormat.of().parseHex(hash.substring(33))) : passwords.matches(password,hash);
        if(rows.isEmpty() || !valid || !Boolean.TRUE.equals(rows.getFirst().get("enabled"))) throw new ApiException(401,"Email or password is incorrect.");
        if(legacy) db.update("UPDATE users SET password_hash=? WHERE email=? AND password_hash=?",passwords.encode(password),email,hash);
        return signedIn(email,req,res);
    }
    @PostMapping("/auth/register") @Transactional Map<String,Object> register(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res) {
        limited(req); String name=Input.text(body,"name",2,100),email=Input.email(body,"email"),password=password(body,true);
        db.update("INSERT INTO users(name,email,password_hash,role) VALUES (?,?,?,'student')",name,email,passwords.encode(password));
        return signedIn(email,req,res);
    }
    @PostMapping("/auth/logout") Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res) {
        access.user(); var session=req.getSession(false); if(session!=null) session.invalidate(); SecurityContextHolder.clearContext();
        return Map.of("signedOut",true);
    }
}
