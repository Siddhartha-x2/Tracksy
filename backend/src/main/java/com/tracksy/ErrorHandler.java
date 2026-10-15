package com.tracksy;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.dao.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger logger=LoggerFactory.getLogger(ErrorHandler.class);
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler({DuplicateKeyException.class,DataIntegrityViolationException.class}) ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("error","This change conflicts with an existing record. Refresh and try again.")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> upload(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Upload one file of 10 MB or less.")); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestPartException.class,MultipartException.class}) ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","The request fields are invalid.")); }
    @ExceptionHandler({NoResourceFoundException.class,NoHandlerFoundException.class}) ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("error","The requested route was not found.")); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e) { return ResponseEntity.status(405).body(Map.of("error","This HTTP method is not supported for this route.")); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class) ResponseEntity<?> content(Exception e) { return ResponseEntity.status(415).body(Map.of("error","This content type is not supported for this route.")); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) { logger.error("API request failed",e); return ResponseEntity.status(500).body(Map.of("error","Something went wrong. Please try again.")); }
}
