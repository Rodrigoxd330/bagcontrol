package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> manejarJsonInvalido(
            HttpMessageNotReadableException exception
    ) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "El cuerpo JSON es inválido o contiene valores incompatibles."));
    }
}
