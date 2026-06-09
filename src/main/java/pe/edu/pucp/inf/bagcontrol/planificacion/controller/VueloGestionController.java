package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class VueloGestionController {

    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;

    // ──────────────── GET /api/vuelos ────────────────
    @GetMapping("/api/vuelos")
    public List<Vuelo> listarVuelos() {
        return vueloRepository.findAll();
    }

    // ──────────────── POST /api/vuelos ────────────────
    @PostMapping("/api/vuelos")
    public ResponseEntity<?> crearVuelo(@RequestBody Vuelo vuelo) {
        String error = validarVuelo(vuelo);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        Vuelo guardado = vueloRepository.save(vuelo);
        return ResponseEntity.status(HttpStatus.CREATED).body(guardado);
    }

    // ──────────────── PUT /api/vuelos/{id} ────────────────
    @PutMapping("/api/vuelos/{id}")
    public ResponseEntity<?> actualizarVuelo(@PathVariable Long id, @RequestBody Vuelo datos) {
        return vueloRepository.findById(id)
                .map(existente -> {
                    if (datos.getOrigenIata() != null)   existente.setOrigenIata(datos.getOrigenIata());
                    if (datos.getDestinoIata() != null)  existente.setDestinoIata(datos.getDestinoIata());
                    if (datos.getHoraSalida() != null)   existente.setHoraSalida(datos.getHoraSalida());
                    if (datos.getHoraLlegada() != null)  existente.setHoraLlegada(datos.getHoraLlegada());
                    if (datos.getCapacidadMax() > 0)     existente.setCapacidadMax(datos.getCapacidadMax());

                    String error = validarVuelo(existente);
                    if (error != null) {
                        return ResponseEntity.badRequest().body(Map.of("error", error));
                    }
                    return ResponseEntity.ok(vueloRepository.save(existente));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────── POST /api/vuelos/{id}/cancelar ────────────────
    @PostMapping("/api/vuelos/{id}/cancelar")
    public ResponseEntity<?> cancelarVuelo(@PathVariable Long id) {
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    vuelo.setEstaCancelado(true);
                    return ResponseEntity.ok(vueloRepository.save(vuelo));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────── DELETE /api/vuelos/{id} ────────────────
    @DeleteMapping("/api/vuelos/{id}")
    public ResponseEntity<?> eliminarVuelo(@PathVariable Long id) {
        if (!vueloRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        vueloRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ──────────────── Validación interna ────────────────
    private String validarVuelo(Vuelo v) {
        if (v.getOrigenIata() == null || v.getOrigenIata().isBlank()) {
            return "El origen es obligatorio";
        }
        if (v.getDestinoIata() == null || v.getDestinoIata().isBlank()) {
            return "El destino es obligatorio";
        }
        if (v.getOrigenIata().equals(v.getDestinoIata())) {
            return "El origen y destino no pueden ser iguales";
        }
        if (!aeropuertoRepository.existsById(v.getOrigenIata())) {
            return "Aeropuerto origen no existe: " + v.getOrigenIata();
        }
        if (!aeropuertoRepository.existsById(v.getDestinoIata())) {
            return "Aeropuerto destino no existe: " + v.getDestinoIata();
        }
        if (v.getHoraSalida() != null && v.getHoraLlegada() != null
                && !v.getHoraSalida().isBefore(v.getHoraLlegada())) {
            return "La hora de salida debe ser anterior a la hora de llegada";
        }
        if (v.getCapacidadMax() <= 0) {
            return "La capacidad máxima debe ser mayor a 0";
        }
        return null;
    }
}
