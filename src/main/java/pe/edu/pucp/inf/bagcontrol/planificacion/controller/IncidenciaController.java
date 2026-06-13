package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.Incidencia;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.IncidenciaRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.IncidenciaDTO;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class IncidenciaController {

    private final IncidenciaRepository incidenciaRepository;

    @GetMapping("/api/incidencias")
    public List<IncidenciaDTO> listarIncidencias() {
        return incidenciaRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/api/incidencias")
    public ResponseEntity<IncidenciaDTO> crearIncidencia(@RequestBody IncidenciaDTO dto) {
        Incidencia incidencia = new Incidencia();
        aplicarCampos(incidencia, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(incidenciaRepository.save(incidencia)));
    }

    @PutMapping("/api/incidencias/{id}")
    public ResponseEntity<IncidenciaDTO> actualizarIncidencia(
            @PathVariable Long id,
            @RequestBody IncidenciaDTO dto
    ) {
        return incidenciaRepository.findById(id)
                .map(incidencia -> {
                    aplicarCampos(incidencia, dto);
                    return ResponseEntity.ok(toDto(incidenciaRepository.save(incidencia)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/incidencias/{id}")
    public ResponseEntity<Void> eliminarIncidencia(@PathVariable Long id) {
        if (!incidenciaRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        incidenciaRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void aplicarCampos(Incidencia incidencia, IncidenciaDTO dto) {
        incidencia.setFechaHora(dto.getFechaHora());
        incidencia.setDescripcion(dto.getDescripcion());
        incidencia.setOrigenIata(dto.getOrigenIata() != null ? dto.getOrigenIata().toUpperCase() : null);
        incidencia.setNoPuedeRecibir(dto.isNoPuedeRecibir());
        incidencia.setNoPuedeEnviar(dto.isNoPuedeEnviar());
        incidencia.setTiempoRecuperacionMinutos(dto.getTiempoRecuperacionMinutos());
    }

    private IncidenciaDTO toDto(Incidencia incidencia) {
        return new IncidenciaDTO(
                incidencia.getId(),
                incidencia.getFechaHora(),
                incidencia.getDescripcion(),
                incidencia.getOrigenIata(),
                incidencia.isNoPuedeRecibir(),
                incidencia.isNoPuedeEnviar(),
                incidencia.getTiempoRecuperacionMinutos()
        );
    }
}
