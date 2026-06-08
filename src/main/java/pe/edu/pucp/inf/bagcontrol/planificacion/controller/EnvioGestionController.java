package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class EnvioGestionController {

    private final EnvioDataStore envioDataStore;
    private final AeropuertoRepository aeropuertoRepository;

    // ──────────────── POST /api/envios ────────────────
    @PostMapping("/api/envios")
    public ResponseEntity<?> registrarEnvio(@RequestBody Envio envio) {

        // Validaciones básicas
        if (envio.getOrigenIata() == null || envio.getOrigenIata().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El aeropuerto de origen es obligatorio"));
        }
        if (envio.getDestinoIata() == null || envio.getDestinoIata().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El aeropuerto de destino es obligatorio"));
        }
        if (envio.getOrigenIata().equals(envio.getDestinoIata())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Origen y destino no pueden ser iguales"));
        }
        if (envio.getCantidadMaletas() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "La cantidad de maletas debe ser mayor a 0"));
        }
        if (envio.getFechaHora() == null) {
            envio.setFechaHora(LocalDateTime.now());
        }

        // Validar que los aeropuertos existen
        Aeropuerto aeropuertoOrigen = aeropuertoRepository.findById(envio.getOrigenIata()).orElse(null);
        if (aeropuertoOrigen == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aeropuerto origen no existe: " + envio.getOrigenIata()));
        }
        if (!aeropuertoRepository.existsById(envio.getDestinoIata())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Aeropuerto destino no existe: " + envio.getDestinoIata()));
        }

        // Generar id si no viene
        if (envio.getIdPedido() == null || envio.getIdPedido().isBlank()) {
            envio.setIdPedido("ENV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }

        envioDataStore.agregarEnvio(envio, aeropuertoOrigen);

        return ResponseEntity.status(HttpStatus.CREATED).body(envio);
    }

    // ──────────────── GET /api/envios/buscar?idPedido=XXX ────────────────
    // Busca en una ventana amplia (los últimos 30 días + próximos 30 días)
    @GetMapping("/api/envios/buscar")
    public ResponseEntity<?> buscarEnvio(@RequestParam String idPedido) {
        LocalDateTime inicio = LocalDateTime.now().minusDays(30);
        LocalDateTime fin    = LocalDateTime.now().plusDays(30);

        List<Envio> encontrados = envioDataStore.obtenerEnviosEnVentana(inicio, fin)
                .stream()
                .filter(e -> idPedido.equals(e.getIdPedido()))
                .toList();

        if (encontrados.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(encontrados.get(0));
    }
}
