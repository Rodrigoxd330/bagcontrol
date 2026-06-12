package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

@RestController
@RequiredArgsConstructor
public class EnvioController {

    private final EnvioDataStore envioDataStore;
    private final AeropuertoRepository aeropuertoRepository;

    @PostMapping("/api/envios")
    public ResponseEntity<EnvioDTO> registrarEnvio(@RequestBody NuevoEnvioDTO dto) {
        if (dto.getOrigenIata() == null || dto.getDestinoIata() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (dto.getOrigenIata().equals(dto.getDestinoIata())) {
            return ResponseEntity.badRequest().build();
        }
        if (dto.getCantidadMaletas() < 1) {
            return ResponseEntity.badRequest().build();
        }

        Aeropuerto aeropuertoOrigen = aeropuertoRepository.findById(dto.getOrigenIata()).orElse(null);
        if (aeropuertoOrigen == null) {
            return ResponseEntity.notFound().build();
        }

        Envio envio = envioDataStore.agregarEnvio(dto, aeropuertoOrigen);

        EnvioDTO respuesta = new EnvioDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getFechaHora() != null ? envio.getFechaHora().toString() : null,
                envio.getCantidadMaletas(),
                envio.getIdCliente()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
    }
}
