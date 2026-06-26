package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.EnvioCrudService;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.ZonaHorariaUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class EnvioController {

    private final EnvioDataStore envioDataStore;
    private final AeropuertoRepository aeropuertoRepository;
    private final EnvioCrudService envioCrudService;

    @PostMapping("/api/envios")
    public ResponseEntity<EnvioDTO> registrarEnvio(@RequestBody NuevoEnvioDTO dto) {
        ResponseEntity<EnvioDTO> error = validar(dto);
        if (error != null) {
            return error;
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(envioCrudService.crear(dto)));
    }

    @PutMapping("/api/envios/{idPedido}")
    public ResponseEntity<EnvioDTO> actualizarEnvio(
            @PathVariable String idPedido,
            @RequestBody NuevoEnvioDTO dto
    ) {
        ResponseEntity<EnvioDTO> error = validar(dto);
        if (error != null) {
            return error;
        }
        return envioCrudService.actualizar(idPedido, dto)
                .map(envio -> ResponseEntity.ok(toDto(envio)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/envios/{idPedido}")
    public ResponseEntity<Void> eliminarEnvio(@PathVariable String idPedido) {
        return envioCrudService.eliminar(idPedido)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
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
        return ResponseEntity.ok(toDto(envio));
    }

    private ResponseEntity<EnvioDTO> validar(NuevoEnvioDTO dto) {
        if (dto.getOrigenIata() == null || dto.getDestinoIata() == null) {
            return ResponseEntity.badRequest().build();
        }
        dto.setOrigenIata(dto.getOrigenIata().trim().toUpperCase());
        dto.setDestinoIata(dto.getDestinoIata().trim().toUpperCase());
        if (dto.getOrigenIata().equals(dto.getDestinoIata())
                || dto.getCantidadMaletas() < 1
                || dto.getIdCliente() == null
                || dto.getIdCliente().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (!aeropuertoRepository.existsById(dto.getOrigenIata())
                || !aeropuertoRepository.existsById(dto.getDestinoIata())) {
            return ResponseEntity.notFound().build();
        }
        if (dto.getFechaHora() == null || dto.getFechaHora().isBlank()) {
            dto.setFechaHora(Instant.now().toString());
        }
        try {
            normalizarFechaHoraRegistro(dto);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().build();
        }
        return null;
    }

    private void normalizarFechaHoraRegistro(NuevoEnvioDTO dto) {
        String recibida = dto.getFechaHora();
        Aeropuerto origen = aeropuertoRepository.findById(dto.getOrigenIata())
                .orElseThrow(() -> new IllegalArgumentException("Aeropuerto origen no existe."));
        boolean tieneOffset = recibida.contains("Z")
                || recibida.contains("z")
                || recibida.matches(".*[+-]\\d{2}:?\\d{2}$");
        LocalDateTime normalizadaUtc;
        if (tieneOffset) {
            normalizadaUtc = EnvioDataStore.parsearFechaHoraUtc(recibida);
        } else {
            try {
                LocalDateTime localOrigen = LocalDateTime.parse(recibida);
                Instant instantUtc = ZonaHorariaUtils.convertirLocalAInstant(localOrigen, origen);
                normalizadaUtc = LocalDateTime.ofInstant(instantUtc, ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                normalizadaUtc = EnvioDataStore.parsearFechaHoraUtc(recibida);
            }
        }
        dto.setFechaHora(normalizadaUtc.toInstant(ZoneOffset.UTC).toString());
        System.out.println("[CRUD-ENVIO-FECHA] recibida=" + recibida
                + " normalizadaUtc=" + normalizadaUtc
                + " origen=" + dto.getOrigenIata()
                + " gmt=" + origen.getGmt()
                + " fechaUtcPlanificador=" + dto.getFechaHora());
    }

    private EnvioDTO toDto(Envio envio) {
        return new EnvioDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getFechaHora() != null
                        ? envio.getFechaHora().toInstant(ZoneOffset.UTC).toString()
                        : null,
                envio.getCantidadMaletas(),
                envio.getIdCliente()
        );
    }
}
