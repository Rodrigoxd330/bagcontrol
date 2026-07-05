package pe.edu.pucp.inf.bagcontrol.auth;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@Component
public class UsuariosConfig {

    private List<UsuarioArchivo> usuarios;

    @PostConstruct
    public void cargar() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream is = new ClassPathResource("data/usuarios.json").getInputStream();
            usuarios = mapper.readValue(is, new TypeReference<List<UsuarioArchivo>>() {});
        } catch (Exception e) {
            System.err.println("[USUARIOS-CONFIG] No se pudo cargar usuarios.json: " + e.getMessage());
            usuarios = Collections.emptyList();
        }
    }

    public List<UsuarioArchivo> getUsuarios() {
        return Collections.unmodifiableList(usuarios);
    }

    public record UsuarioArchivo(String email, String password, String nombre, String rol, String aeropuerto) {}
}
