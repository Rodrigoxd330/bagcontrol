package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.Incidencia;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.IncidenciaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/incidencias")
public class IncidenciaController {

    private final IncidenciaRepository incidenciaRepository;

    // ──────────────── GET /api/incidencias ────────────────
    @GetMapping
    public List<Incidencia> listarIncidencias() {
        return incidenciaRepository.findAll();
    }

    // ──────────────── GET /api/incidencias/{id} ────────────────
    @GetMapping("/{id}")
    public ResponseEntity<Incidencia> obtenerIncidencia(@PathVariable Long id) {
        return incidenciaRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────── POST /api/incidencias ────────────────
    @PostMapping
    public ResponseEntity<?> crearIncidencia(@RequestBody Incidencia incidencia) {
        String error = validar(incidencia);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        if (incidencia.getFechaHora() == null) {
            incidencia.setFechaHora(LocalDateTime.now());
        }
        Incidencia guardada = incidenciaRepository.save(incidencia);
        return ResponseEntity.status(HttpStatus.CREATED).body(guardada);
    }

    // ──────────────── PUT /api/incidencias/{id} ────────────────
    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarIncidencia(@PathVariable Long id, @RequestBody Incidencia datos) {
        return incidenciaRepository.findById(id)
                .map(existente -> {
                    if (datos.getDescripcion() != null)        existente.setDescripcion(datos.getDescripcion());
                    if (datos.getOrigenIata() != null)         existente.setOrigenIata(datos.getOrigenIata());
                    if (datos.getFechaHora() != null)          existente.setFechaHora(datos.getFechaHora());
                    if (datos.getTiempoRecuperacionMinutos() > 0)
                        existente.setTiempoRecuperacionMinutos(datos.getTiempoRecuperacionMinutos());

                    // Siempre actualizar los flags booleanos (pueden ser false intencionalmente)
                    existente.setNoPuedeRecibir(datos.isNoPuedeRecibir());
                    existente.setNoPuedeEnviar(datos.isNoPuedeEnviar());

                    return ResponseEntity.ok(incidenciaRepository.save(existente));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────── DELETE /api/incidencias/{id} ────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarIncidencia(@PathVariable Long id) {
        if (!incidenciaRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        incidenciaRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ──────────────── Validación interna ────────────────
    private String validar(Incidencia i) {
        if (i.getOrigenIata() == null || i.getOrigenIata().isBlank()) {
            return "El código IATA del aeropuerto afectado es obligatorio";
        }
        if (!i.isNoPuedeRecibir() && !i.isNoPuedeEnviar()) {
            return "La incidencia debe bloquear al menos una operación (recibir o enviar)";
        }
        if (i.getTiempoRecuperacionMinutos() <= 0) {
            return "El tiempo de recuperación debe ser mayor a 0 minutos";
        }
        return null;
    }
}
