package pe.edu.pucp.inf.bagcontrol.auth;

public record AuthRequest(String email, String password, String nombre) {
}
