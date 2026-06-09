package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.AeropuertoDTO;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AeropuertoConsultaController {

    private final AeropuertoRepository aeropuertoRepository;

    @GetMapping("/api/aeropuertos")
    public List<AeropuertoDTO> listarAeropuertos() {
        return aeropuertoRepository.findAll()
                .stream()
                .map(a -> new AeropuertoDTO(
                        a.getCodigoIata(),
                        a.getCiudad(),
                        a.getPais(),
                        a.getContinente(),
                        a.getCapacidadAlmacen(),
                        a.getGmt(),
                        a.getLatitud(),
                        a.getLongitud(),
                        0
                ))
                .toList();
    }
}
