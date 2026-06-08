package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AeropuertoGestionController {

    private final AeropuertoRepository aeropuertoRepository;
    private final VueloRepository vueloRepository;

    // ──────────────── POST /api/aeropuertos ────────────────
    @PostMapping("/api/aeropuertos")
    public ResponseEntity<?> crearAeropuerto(@RequestBody Aeropuerto aeropuerto) {
        if (aeropuerto.getCodigoIata() == null || aeropuerto.getCodigoIata().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El código IATA es obligatorio"));
        }
        if (aeropuertoRepository.existsById(aeropuerto.getCodigoIata())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Ya existe un aeropuerto con el código IATA: " + aeropuerto.getCodigoIata()));
        }
        Aeropuerto guardado = aeropuertoRepository.save(aeropuerto);
        return ResponseEntity.status(HttpStatus.CREATED).body(guardado);
    }

    // ──────────────── PUT /api/aeropuertos/{iata} ────────────────
    @PutMapping("/api/aeropuertos/{iata}")
    public ResponseEntity<?> actualizarAeropuerto(
            @PathVariable String iata,
            @RequestBody Aeropuerto datos) {

        return aeropuertoRepository.findById(iata)
                .map(existente -> {
                    if (datos.getCiudad() != null)      existente.setCiudad(datos.getCiudad());
                    if (datos.getPais() != null)         existente.setPais(datos.getPais());
                    if (datos.getContinente() != null)   existente.setContinente(datos.getContinente());
                    if (datos.getCapacidadAlmacen() > 0) existente.setCapacidadAlmacen(datos.getCapacidadAlmacen());
                    if (datos.getGmt() != 0)             existente.setGmt(datos.getGmt());
                    if (datos.getLatitud() != 0.0)       existente.setLatitud(datos.getLatitud());
                    if (datos.getLongitud() != 0.0)      existente.setLongitud(datos.getLongitud());
                    return ResponseEntity.ok(aeropuertoRepository.save(existente));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────── DELETE /api/aeropuertos/{iata} ────────────────
    @DeleteMapping("/api/aeropuertos/{iata}")
    public ResponseEntity<?> eliminarAeropuerto(@PathVariable String iata) {
        if (!aeropuertoRepository.existsById(iata)) {
            return ResponseEntity.notFound().build();
        }

        // Validar que no haya vuelos activos que referencien este aeropuerto
        boolean tieneVuelos = vueloRepository.findAll().stream()
                .anyMatch(v -> !v.isEstaCancelado()
                        && (iata.equals(v.getOrigenIata()) || iata.equals(v.getDestinoIata())));

        if (tieneVuelos) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No se puede eliminar: el aeropuerto tiene vuelos activos asociados"));
        }

        aeropuertoRepository.deleteById(iata);
        return ResponseEntity.noContent().build();
    }
}
