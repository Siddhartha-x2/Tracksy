package com.tracksy;

import java.util.Map;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputTest {
    @Test void trimsTextAndRejectsNullCharactersOrWrongTypes() {
        assertEquals("A title",Input.text(Map.of("title","  A title  "),"title",2,100));
        assertThrows(ApiException.class,() -> Input.text(Map.of("title","bad\0title"),"title",2,100));
        assertThrows(ApiException.class,() -> Input.text(Map.of("title",42),"title",2,100));
        assertThrows(ApiException.class,() -> Input.text(Map.of("title","x"),"title",2,100));
    }
    @Test void numbersMustBeFiniteIntegersInRange() {
        assertEquals(5,Input.number(Map.of("quality",5),"quality",1,5));
        for(Object value:new Object[]{"5",4.5,Double.NaN,Double.POSITIVE_INFINITY,0,6})
            assertThrows(ApiException.class,() -> Input.number(Map.of("quality",value),"quality",1,5));
    }
    @Test void datesUseStrictCalendarAndAcademicRange() {
        assertNull(Input.date(Map.of(),"dueDate"));
        assertEquals(LocalDate.of(2024,2,29),Input.date(Map.of("dueDate","2024-02-29"),"dueDate"));
        for(String value:new String[]{"2026-02-29","2026-99-99","1999-12-31","2201-01-01","14/10/2026"})
            assertThrows(ApiException.class,() -> Input.date(Map.of("dueDate",value),"dueDate"));
    }
    @Test void linksCannotExecuteScriptsOrEmbedCredentials() {
        assertEquals("https://example.test/work",Input.url("https://example.test/work"));
        assertEquals("",Input.url(""));
        for(String value:new String[]{"javascript:alert(1)","file:///etc/passwd","https://secret@example.test/work","https://example.test:0/work","https://example.test:65536/work"})
            assertThrows(ApiException.class,() -> Input.url(value));
    }
    @Test void emailAndBooleansUseUnambiguousValues() {
        assertEquals("student@example.test",Input.email(Map.of("email","Student@Example.Test"),"email"));
        assertThrows(ApiException.class,() -> Input.email(Map.of("email","bad address@example.test"),"email"));
        assertTrue(Input.bool(Map.of("accept",true),"accept"));
        assertThrows(ApiException.class,() -> Input.bool(Map.of("accept","true"),"accept"));
    }
}
