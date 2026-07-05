package pe.edu.pucp.inf.bagcontrol.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        if (request == null || request.email() == null || request.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credenciales incompletas.");
        }
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        if (request == null || request.email() == null || request.password() == null || request.nombre() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de registro incompletos.");
        }
        return authService.register(request.email(), request.password(), request.nombre(), request.rol(), request.aeropuerto());
    }

    @GetMapping("/me")
    public AuthResponse obtenerInfoSesion(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return authService.obtenerInfoSesion(authorization);
    }
}
