package com.tracksy;

import java.time.LocalDate;
import java.util.*;
import java.net.URI;

public final class Input {
    private Input() {}
    public static String text(Map<String,Object> body,String key,int min,int max) {
        Object raw=body.get(key);
        if(raw!=null && !(raw instanceof String)) throw new ApiException(400,key+" must be text.");
        String value=raw==null?"":((String)raw).trim();
        if(value.length()<min || value.length()>max) throw new ApiException(400,key+" must contain "+min+" to "+max+" characters.");
        if(value.indexOf('\0')>=0) throw new ApiException(400,"Text cannot contain null characters.");
        return value;
    }
    public static String choice(Map<String,Object> b,String key,String defaultValue,String... choices) {
        String value=b.containsKey(key)?text(b,key,1,100):defaultValue;
        if(!Arrays.asList(choices).contains(value)) throw new ApiException(400,"Choose a valid "+key+".");
        return value;
    }
    public static long number(Map<String,Object> b,String key,long min,long max) {
        Object raw=b.get(key);
        if(!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.longValue() || n.longValue()<min || n.longValue()>max) throw new ApiException(400,"Choose a valid "+key+".");
        return ((Number)raw).longValue();
    }
    public static boolean bool(Map<String,Object> b,String key) {
        if(!(b.get(key) instanceof Boolean value)) throw new ApiException(400,key+" must be true or false.");
        return value;
    }
    public static LocalDate date(Map<String,Object> b,String key) {
        if(!b.containsKey(key) || b.get(key)==null || "".equals(b.get(key))) return null;
        String value=text(b,key,10,10);
        try { LocalDate date=LocalDate.parse(value); if(date.getYear()<2000 || date.getYear()>2200) throw new Exception(); return date; }
        catch(Exception e) { throw new ApiException(400,"Enter a valid "+key+" in YYYY-MM-DD format."); }
    }
    public static String email(Map<String,Object> b,String key) {
        String value=text(b,key,5,254).toLowerCase(Locale.ROOT);
        if(!value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ApiException(400,"Enter a valid email address.");
        return value;
    }
    public static String url(String raw) {
        if(raw.isEmpty()) return raw;
        try { var uri=new URI(raw); if(!List.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getPort()>65535 || uri.getPort()==0) throw new Exception(); String encoded=uri.toASCIIString(); if(encoded.length()>2000) throw new Exception(); return encoded; }
        catch(Exception e) { throw new ApiException(400,"Work links must use a valid HTTP or HTTPS URL without credentials."); }
    }
    public static String id() { return UUID.randomUUID().toString(); }
}
