package com.tracksy.integrations;

import com.tracksy.Access;
import com.tracksy.ApiException;
import com.tracksy.Db;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** Optional durable email delivery. Requires SMTP configuration and verified, explicit user opt-in. */
@Component
@RestController
@EnableScheduling
@RequestMapping("/api/integrations/email")
public class EmailNotifications {
 private final Db db; private final Access access; private final JavaMailSenderImpl sender;
 private final String from,appUrl; private final boolean configured;
 public EmailNotifications(Db db,Access access,
  @Value("${SMTP_HOST:}") String host, @Value("${SMTP_PORT:587}") int port,
  @Value("${SMTP_USER:}") String username,@Value("${SMTP_PASSWORD:}") String password,
  @Value("${SMTP_FROM:}") String from,@Value("${SMTP_SSL:false}") boolean ssl,
  @Value("${PUBLIC_APP_URL:http://localhost:4000}") String appUrl) {
  this.db=db;this.access=access;this.from=from;this.appUrl=appUrl;
  URI url=URI.create(appUrl);boolean local=Set.of("localhost","127.0.0.1").contains(url.getHost());
  configured=!host.isBlank()&&!username.isBlank()&&!password.isBlank()&&from.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
   &&url.getUserInfo()==null&&(url.getScheme().equals("https")||local&&url.getScheme().equals("http"));
  sender=new JavaMailSenderImpl();sender.setHost(host);sender.setPort(port);sender.setUsername(username);sender.setPassword(password);
  var properties=sender.getJavaMailProperties();properties.setProperty("mail.smtp.auth","true");
  properties.setProperty("mail.smtp.ssl.enable",String.valueOf(ssl));properties.setProperty("mail.smtp.starttls.enable",String.valueOf(!ssl));
  properties.setProperty("mail.smtp.starttls.required",String.valueOf(!ssl));properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
  properties.setProperty("mail.smtp.connectiontimeout","5000");properties.setProperty("mail.smtp.timeout","5000");properties.setProperty("mail.smtp.writetimeout","5000");
 }
 private static String digest(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private void ensure(long id){db.update("INSERT INTO email_preferences(user_id) VALUES(?) ON CONFLICT DO NOTHING",id);}
 @GetMapping("/status") public Map<String,Object> status(){
  var user=access.user();ensure(user.id());var p=db.one("SELECT * FROM email_preferences WHERE user_id=?",user.id());
  return Map.of("configured",configured,"verified",p.get("verified_at")!=null,"enabled",p.get("enabled"),"email",user.email(),"message",configured?"Verify your address, then opt in to project notifications by email.":"Email delivery is disabled until SMTP_HOST, SMTP_USER, SMTP_PASSWORD and SMTP_FROM are configured.");
 }
 @PostMapping("/verify") public Map<String,Object> verify(){
  var user=access.user();if(!configured)throw new ApiException(503,"Email delivery is not configured.");ensure(user.id());
  int allowed=db.update("UPDATE email_preferences SET request_count=CASE WHEN last_request_at IS NULL OR last_request_at<now()-interval '1 hour' THEN 1 ELSE request_count+1 END,last_request_at=now() WHERE user_id=? AND (last_request_at IS NULL OR last_request_at<now()-interval '1 hour' OR request_count<3)",user.id());
  if(allowed==0)throw new ApiException(429,"Verification limit reached. Try again in an hour.");
  byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  db.update("DELETE FROM email_verification_tokens WHERE user_id=? OR expires_at<now()",user.id());
  db.update("INSERT INTO email_verification_tokens(token_hash,user_id,expires_at) VALUES(?,?,now()+interval '20 minutes')",digest(token),user.id());
  try{send(user.email(),"Verify your Tracksy email", "Verify your own Tracksy email address using this link while signed in to your account:\n\n"+appUrl+"/api/integrations/email/confirm?token="+token+"\n\nThis link expires in 20 minutes. If you did not request it, ignore this email.");}
  catch(Exception e){db.update("DELETE FROM email_verification_tokens WHERE token_hash=?",digest(token));throw new ApiException(502,"Email could not be delivered. Check the server SMTP configuration.");}
  return Map.of("sent",true,"message","Verification was sent to your account email address.");
 }
 @GetMapping("/confirm") public ResponseEntity<Void> confirm(@RequestParam String token){
  var user=access.user();if(token.length()>100)throw new ApiException(400,"Invalid verification link.");
  var rows=db.query("DELETE FROM email_verification_tokens WHERE token_hash=? AND user_id=? AND expires_at>now() RETURNING user_id",digest(token),user.id());
  if(rows.isEmpty())throw new ApiException(400,"This verification link expired or was already used.");
  ensure(user.id());db.update("UPDATE email_preferences SET verified_at=now() WHERE user_id=?",user.id());
  return ResponseEntity.status(303).location(URI.create("/?email=verified")).build();
 }
 @PostMapping("/preferences") public Map<String,Object> preferences(@RequestBody Map<String,Object> input){
  var user=access.user();if(!(input.get("enabled") instanceof Boolean enabled))throw new ApiException(400,"Choose whether to enable email notifications.");
  ensure(user.id());var p=db.one("SELECT * FROM email_preferences WHERE user_id=?",user.id());
  if(enabled&&(!configured||p.get("verified_at")==null))throw new ApiException(409,"Verify your email address and configure delivery before enabling notifications.");
  db.update("UPDATE email_preferences SET enabled=?,enabled_at=CASE WHEN ? THEN COALESCE(enabled_at,now()) ELSE NULL END WHERE user_id=?",enabled,enabled,user.id());
  return status();
 }
 private void send(String to,String title,String body){var message=new SimpleMailMessage();message.setFrom(from);message.setTo(to);message.setSubject(title);message.setText(body);sender.send(message);}
 @Scheduled(fixedDelay=60000,initialDelay=60000) public void deliver(){
  if(!configured)return;
  db.update("INSERT INTO email_deliveries(notification_id) SELECT n.id FROM notifications n JOIN email_preferences p ON p.user_id=n.user_id JOIN users u ON u.id=n.user_id WHERE p.enabled AND p.verified_at IS NOT NULL AND u.enabled AND n.created_at>=p.enabled_at ON CONFLICT DO NOTHING");
  var rows=db.query("SELECT d.notification_id,n.title,n.body,u.email FROM email_deliveries d JOIN notifications n ON n.id=d.notification_id JOIN users u ON u.id=n.user_id JOIN email_preferences p ON p.user_id=u.id WHERE d.status<>'Sent' AND d.attempts<3 AND d.retry_at<=now() AND u.enabled AND p.enabled AND p.verified_at IS NOT NULL ORDER BY d.notification_id LIMIT 20");
  for(var row:rows){long id=((Number)row.get("notification_id")).longValue();
   // Reserve before network IO so a restarted worker does not immediately duplicate an attempt.
   if(db.update("UPDATE email_deliveries SET attempts=attempts+1,retry_at=now()+interval '10 minutes',updated_at=now() WHERE notification_id=? AND status<>'Sent' AND retry_at<=now() AND attempts<3",id)==0)continue;
   try{send((String)row.get("email"),"Tracksy · "+row.get("title"),row.get("body")+"\n\nOpen your workspace: "+appUrl+"\nManage email preferences in Tracksy settings.");db.update("UPDATE email_deliveries SET status='Sent',sent_at=now(),updated_at=now() WHERE notification_id=?",id);}
   catch(Exception error){db.update("UPDATE email_deliveries SET status='Failed',updated_at=now() WHERE notification_id=?",id);}
  }
 }
}
