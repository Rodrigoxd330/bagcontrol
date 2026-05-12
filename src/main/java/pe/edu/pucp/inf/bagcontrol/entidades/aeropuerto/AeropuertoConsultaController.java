package pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.service.AeropuertoService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AeropuertoConsultaController {

    private final AeropuertoService aeropuertoService;

    @GetMapping("/api/aeropuertos")
    public List<Aeropuerto> listarAeropuertos() {
        return aeropuertoService.findAll();
    }
}
