package com.tracksy;

import java.util.*;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
    private final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }
    public List<Map<String,Object>> query(String sql, Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql, Object... args) {
        var result = query(sql,args);
        if(result.isEmpty()) throw new ApiException(404,"The requested record was not found.");
        return result.getFirst();
    }
    public int update(String sql, Object... args) { return jdbc.update(sql,args); }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    public static Map<String,Object> dto(Map<String,Object> row) {
        Map<String,Object> output = new LinkedHashMap<>();
        row.forEach((key,value) -> {
            StringBuilder camel = new StringBuilder(); boolean upper = false;
            for(char c:key.toCharArray()) { if(c=='_') upper=true; else { camel.append(upper?Character.toUpperCase(c):c); upper=false; } }
            Object normalized = value instanceof Timestamp t ? t.toInstant().toString() : value instanceof java.sql.Date d ? d.toLocalDate().toString() : value;
            output.put(camel.toString(),normalized);
        });
        return output;
    }
}
