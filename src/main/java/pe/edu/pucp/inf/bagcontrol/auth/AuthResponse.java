package pe.edu.pucp.inf.bagcontrol.auth;

public record AuthResponse(String token, String email, String nombre, String rol, String aeropuerto) {
}
