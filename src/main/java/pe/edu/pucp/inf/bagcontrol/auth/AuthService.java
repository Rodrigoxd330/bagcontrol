package pe.edu.pucp.inf.bagcontrol.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final Map<String, UsuarioInterno> usuariosPorEmail = new ConcurrentHashMap<>();
    private final Map<String, UsuarioSesion> sesionesPorToken = new ConcurrentHashMap<>();

    public AuthService() {
        registrarInicial("admin@bagcontrol.com", "admin123", "admin");
        registrarInicial("demo@bagcontrol.com", "demo123", "demo");
    }

    public AuthResponse login(String email, String password) {
        String emailNormalizado = normalizarEmail(email);
        UsuarioInterno usuario = usuariosPorEmail.get(emailNormalizado);
        if (usuario == null || !usuario.passwordHash().equals(hash(password))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas.");
        }
        return crearSesion(usuario.email(), usuario.nombre());
    }

    public AuthResponse register(String email, String password, String nombre) {
        String emailNormalizado = normalizarEmail(email);
        validarPassword(password);
        String nombreNormalizado = nombre == null ? "" : nombre.trim();
        if (nombreNormalizado.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio.");
        }
        UsuarioInterno nuevo = new UsuarioInterno(emailNormalizado, hash(password), nombreNormalizado);
        UsuarioInterno anterior = usuariosPorEmail.putIfAbsent(emailNormalizado, nuevo);
        if (anterior != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una cuenta con ese correo.");
        }
        return crearSesion(nuevo.email(), nuevo.nombre());
    }

    public UsuarioSesion resolverBearer(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String prefijo = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefijo, 0, prefijo.length())) {
            return null;
        }
        return sesionesPorToken.get(authorizationHeader.substring(prefijo.length()).trim());
    }

    private void registrarInicial(String email, String password, String nombre) {
        usuariosPorEmail.put(normalizarEmail(email), new UsuarioInterno(normalizarEmail(email), hash(password), nombre));
    }

    private AuthResponse crearSesion(String email, String nombre) {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((UUID.randomUUID() + ":" + email + ":" + Instant.now()).getBytes(StandardCharsets.UTF_8));
        sesionesPorToken.put(token, new UsuarioSesion(email, nombre));
        return new AuthResponse(token, email, nombre);
    }

    private String normalizarEmail(String email) {
        String normalizado = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalizado.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Correo invalido.");
        }
        return normalizado;
    }

    private void validarPassword(String password) {
        if (password == null || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contrasena debe tener al menos 6 caracteres.");
        }
    }

    private String hash(String password) {
        validarPassword(password);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible.", e);
        }
    }

    private record UsuarioInterno(String email, String passwordHash, String nombre) {
    }
}
