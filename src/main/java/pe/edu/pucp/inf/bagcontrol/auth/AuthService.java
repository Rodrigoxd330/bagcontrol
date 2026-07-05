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

    public AuthService(UsuariosConfig config) {
        for (UsuariosConfig.UsuarioArchivo u : config.getUsuarios()) {
            registrarInicial(u.email(), u.password(), u.nombre(), u.rol(), u.aeropuerto());
        }
    }

    public AuthResponse login(String email, String password) {
        String emailNormalizado = normalizarEmail(email);
        UsuarioInterno usuario = usuariosPorEmail.get(emailNormalizado);
        if (usuario == null || !usuario.passwordHash().equals(hash(password))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas.");
        }
        return crearSesion(usuario.email(), usuario.nombre(), usuario.rol(), usuario.aeropuerto());
    }

    public AuthResponse register(String email, String password, String nombre, String rol, String aeropuerto) {
        String emailNormalizado = normalizarEmail(email);
        validarPassword(password);
        String nombreNormalizado = nombre == null ? "" : nombre.trim();
        if (nombreNormalizado.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio.");
        }
        String rolNormalizado = rol == null || rol.isBlank() ? "REGISTRADOR" : rol.trim().toUpperCase();
        String aeropuertoNormalizado = aeropuerto == null ? "" : aeropuerto.trim().toUpperCase();
        UsuarioInterno nuevo = new UsuarioInterno(emailNormalizado, hash(password), nombreNormalizado, rolNormalizado, aeropuertoNormalizado);
        UsuarioInterno anterior = usuariosPorEmail.putIfAbsent(emailNormalizado, nuevo);
        if (anterior != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una cuenta con ese correo.");
        }
        return crearSesion(nuevo.email(), nuevo.nombre(), nuevo.rol(), nuevo.aeropuerto());
    }

    public AuthResponse obtenerInfoSesion(String authorizationHeader) {
        UsuarioSesion sesion = resolverBearer(authorizationHeader);
        if (sesion == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion no encontrada.");
        }
        return new AuthResponse(null, sesion.email(), sesion.nombre(), sesion.rol(), sesion.aeropuerto());
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

    private void registrarInicial(String email, String password, String nombre, String rol, String aeropuerto) {
        String emailNormalizado = normalizarEmail(email);
        usuariosPorEmail.put(emailNormalizado, new UsuarioInterno(emailNormalizado, hash(password), nombre, rol, aeropuerto));
    }

    private AuthResponse crearSesion(String email, String nombre, String rol, String aeropuerto) {
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((UUID.randomUUID() + ":" + email + ":" + Instant.now()).getBytes(StandardCharsets.UTF_8));
        sesionesPorToken.put(token, new UsuarioSesion(email, nombre, rol, aeropuerto));
        return new AuthResponse(token, email, nombre, rol, aeropuerto);
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

    private record UsuarioInterno(String email, String passwordHash, String nombre, String rol, String aeropuerto) {
    }
}
