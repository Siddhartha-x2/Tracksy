package com.tracksy.integrations;

import com.tracksy.Access;
import com.tracksy.ApiException;
import com.tracksy.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Verifies account ownership through GitHub OAuth; access tokens are used once, never stored. */
@RestController
@RequestMapping("/api/integrations/github")
public class GitHubIdentityController {
 private final Db db;
 private final Access access;
 private final ObjectMapper json;
 private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final String clientId, clientSecret, redirectUri;
 public GitHubIdentityController(Db db, Access access, ObjectMapper json,
   @Value("${GITHUB_CLIENT_ID:}") String clientId, @Value("${GITHUB_CLIENT_SECRET:}") String clientSecret,
   @Value("${GITHUB_REDIRECT_URI:http://localhost:4000/api/integrations/github/callback}") String redirectUri) {
  this.db=db; this.access=access; this.json=json; this.clientId=clientId; this.clientSecret=clientSecret; this.redirectUri=redirectUri;
 }
 private boolean configured() { return !clientId.isBlank() && !clientSecret.isBlank(); }
 private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
 private static String digest(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch(Exception error) { throw new IllegalStateException(error); }
 }
 @GetMapping("/status")
 public Map<String,Object> status() {
  var user=access.user(); var identity=db.query("SELECT github_username,verified_at FROM user_github_identities WHERE user_id=?",user.id());
  var result=new LinkedHashMap<String,Object>(); result.put("configured",configured()); result.put("connected",!identity.isEmpty());
  result.put("username",identity.isEmpty()?null:identity.getFirst().get("github_username"));
  result.put("verifiedAt",identity.isEmpty()?null:identity.getFirst().get("verified_at"));
  result.put("scope","Verified identity and public repository activity");
  result.put("message",configured()?"GitHub account ownership is verified through OAuth.":"Set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET to enable GitHub account linking. Public repository browsing remains available.");
  return result;
 }
 @PostMapping("/connect")
 public Map<String,Object> connect(HttpServletRequest request) {
  var user=access.user();
  if(!configured()) throw new ApiException(503,"GitHub OAuth is not configured. Set the server's GitHub client credentials first.");
  URI callback=URI.create(redirectUri);
  boolean loopback=Set.of("localhost","127.0.0.1").contains(callback.getHost());
  if(callback.getUserInfo()!=null || callback.getFragment()!=null || !("https".equals(callback.getScheme()) || loopback && "http".equals(callback.getScheme())))
   throw new ApiException(503,"GitHub callback must use HTTPS or a local development address.");
  byte[] random=new byte[32]; new SecureRandom().nextBytes(random); String state=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  db.update("DELETE FROM github_oauth_states WHERE expires_at < now() OR user_id=?",user.id());
  db.update("INSERT INTO github_oauth_states(state_hash,user_id,session_hash,expires_at) VALUES (?,?,?,now()+interval '10 minutes')",digest(state),user.id(),digest(request.getSession().getId()));
  return Map.of("authorizeUrl","https://github.com/login/oauth/authorize?client_id="+encode(clientId)+"&redirect_uri="+encode(redirectUri)+"&scope=read%3Auser&state="+encode(state));
 }
 @GetMapping("/callback")
 public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam(required=false) String code, HttpServletRequest request) {
  var user=access.user();
  if(state.length()>200 || code!=null && code.length()>500) throw new ApiException(400,"Invalid OAuth callback.");
  var states=db.query("DELETE FROM github_oauth_states WHERE state_hash=? AND user_id=? AND session_hash=? AND expires_at>now() RETURNING user_id",digest(state),user.id(),digest(request.getSession().getId()));
  if(states.isEmpty()) throw new ApiException(400,"This GitHub connection expired or was already used. Start linking again.");
  if(code==null || code.isBlank()) return ResponseEntity.status(303).location(URI.create("/?github=cancelled")).build();
  try {
   String body="client_id="+encode(clientId)+"&client_secret="+encode(clientSecret)+"&code="+encode(code)+"&redirect_uri="+encode(redirectUri);
   var exchange=http.send(HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token")).timeout(Duration.ofSeconds(12)).header("Accept","application/json").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
   if(exchange.statusCode()!=200 || exchange.body().length()>64000) throw new ApiException(502,"GitHub did not accept the connection. Start linking again.");
   var token=json.readTree(exchange.body()).path("access_token").asText();
   if(token.isBlank()) throw new ApiException(502,"GitHub did not provide an access token. Start linking again.");
   var response=http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/user")).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+token).header("Accept","application/vnd.github+json").header("User-Agent","Tracksy/1.0").GET().build(),HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200 || response.body().length()>64000) throw new ApiException(502,"GitHub identity verification failed.");
   var identity=json.readTree(response.body()); long githubId=identity.path("id").asLong(); String username=identity.path("login").asText();
   if(githubId<=0 || !username.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) throw new ApiException(502,"GitHub returned an invalid identity.");
   var duplicates=db.query("SELECT user_id FROM user_github_identities WHERE github_id=? AND user_id<>?",githubId,user.id());
   if(!duplicates.isEmpty()) throw new ApiException(409,"That GitHub identity is already linked to another Tracksy account.");
   db.update("INSERT INTO user_github_identities(user_id,github_id,github_username) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET github_id=excluded.github_id,github_username=excluded.github_username,verified_at=now()",user.id(),githubId,username);
   access.audit(null,"GitHub identity linked","GitHub identity",String.valueOf(user.id()),"Account ownership verified through GitHub OAuth.",user.id());
   return ResponseEntity.status(303).location(URI.create("/?github=connected")).build();
  } catch(ApiException error) { throw error; }
  catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new ApiException(502,"GitHub connection was interrupted. Start linking again."); }
  catch(Exception error) { throw new ApiException(502,"GitHub is temporarily unavailable. Start linking again."); }
 }
 @DeleteMapping("/disconnect")
 public Map<String,Object> disconnect() {
  var user=access.user(); db.update("DELETE FROM user_github_identities WHERE user_id=?",user.id()); db.update("DELETE FROM github_oauth_states WHERE user_id=?",user.id());
  access.audit(null,"GitHub identity disconnected","GitHub identity",String.valueOf(user.id()),"Future repository scoring requires a new verified connection.",user.id());
  return Map.of("connected",false);
 }
}
