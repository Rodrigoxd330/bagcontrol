package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

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
        dto.setOrigenIata(dto.getOrigenIata().toUpperCase());
        dto.setDestinoIata(dto.getDestinoIata().toUpperCase());
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
        if (!aeropuertoRepository.existsById(dto.getDestinoIata())) {
            return ResponseEntity.notFound().build();
        }
        if (dto.getFechaHora() == null || dto.getFechaHora().isBlank()) {
            dto.setFechaHora(Instant.now().toString());
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

    @GetMapping("/api/envios/buscar")
    public ResponseEntity<EnvioDTO> buscarEnvio(@RequestParam String idPedido) {
        LocalDateTime inicio = LocalDateTime.now().minusDays(30);
        LocalDateTime fin = LocalDateTime.now().plusDays(30);

        List<Envio> encontrados = envioDataStore.obtenerEnviosEnVentana(inicio, fin)
                .stream()
                .filter(envio -> idPedido.equals(envio.getIdPedido()))
                .toList();

        if (encontrados.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Envio envio = encontrados.get(0);
        return ResponseEntity.ok(new EnvioDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getFechaHora() != null ? envio.getFechaHora().toString() : null,
                envio.getCantidadMaletas(),
                envio.getIdCliente()
        ));
    }
}
