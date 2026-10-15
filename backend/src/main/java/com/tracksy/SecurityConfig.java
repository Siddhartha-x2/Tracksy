package com.tracksy;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import com.fasterxml.jackson.core.StreamReadConstraints;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new CompatiblePasswordEncoder(); }
    @Bean Jackson2ObjectMapperBuilderCustomizer boundedJson() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(1024*1024).maxNestingDepth(32).maxStringLength(10000).maxNumberLength(32).build()));
    }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean UserDetailsService userDetailsService(Db db) {
        return email -> {
            var rows=db.query("SELECT email,password_hash,role,enabled FROM users WHERE email=?",email.toLowerCase(Locale.ROOT));
            if(rows.isEmpty()) throw new UsernameNotFoundException("Account unavailable");
            var row=rows.getFirst();
            return org.springframework.security.core.userdetails.User.withUsername((String)row.get("email"))
                .password((String)row.get("password_hash")).roles(((String)row.get("role")).toUpperCase(Locale.ROOT))
                .disabled(!Boolean.TRUE.equals(row.get("enabled"))).build();
        };
    }
    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts,
            @Value("${tracksy.origins}") String origins) throws Exception {
        Set<String> allowed=new HashSet<>(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        http.securityContext(c -> c.securityContextRepository(contexts))
            .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/csrf","/api/auth/config","/api/auth/login","/api/auth/register","/api/health").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req,res,error) -> json(res,401,"Sign in to continue."))
                .accessDeniedHandler((req,res,error) -> json(res,403,"This request was not authorized. Refresh the page and try again.")))
            .logout(l -> l.disable())
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    if(req.getRequestURI().startsWith("/api/")) {
                        res.setHeader("Cache-Control","no-store");
                        String origin=req.getHeader("Origin");
                        if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()) && origin!=null && !allowed.contains(origin)) {
                            json(res,403,"Request origin is not allowed."); return;
                        }
                    }
                    chain.doFilter(req,res);
                }
            },CsrfFilter.class);
        return http.build();
    }
    private static void json(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json");
        res.getWriter().write("{\"error\":\""+message+"\"}");
    }
}
