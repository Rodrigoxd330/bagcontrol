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
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloDTO;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class VueloController {

    private final VueloRepository vueloRepository;

    @GetMapping("/api/vuelos")
    public List<VueloDTO> listarVuelos() {
        return vueloRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/api/vuelos")
    public ResponseEntity<VueloDTO> crearVuelo(@RequestBody VueloDTO dto) {
        Vuelo vuelo = new Vuelo();
        aplicarCampos(vuelo, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(vueloRepository.save(vuelo)));
    }

    @PutMapping("/api/vuelos/{id}")
    public ResponseEntity<VueloDTO> actualizarVuelo(@PathVariable Long id, @RequestBody VueloDTO dto) {
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    aplicarCampos(vuelo, dto);
                    return ResponseEntity.ok(toDto(vueloRepository.save(vuelo)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/vuelos/{id}")
    public ResponseEntity<Void> eliminarVuelo(@PathVariable Long id) {
        if (!vueloRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        vueloRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/vuelos/{id}/cancelar")
    public ResponseEntity<VueloDTO> cancelarVuelo(@PathVariable Long id) {
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    vuelo.setEstaCancelado(true);
                    return ResponseEntity.ok(toDto(vueloRepository.save(vuelo)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void aplicarCampos(Vuelo vuelo, VueloDTO dto) {
        vuelo.setOrigenIata(dto.getOrigenIata());
        vuelo.setDestinoIata(dto.getDestinoIata());
        vuelo.setHoraSalida(dto.getHoraSalida());
        vuelo.setHoraLlegada(dto.getHoraLlegada());
        vuelo.setCapacidadMax(dto.getCapacidadMax());
        vuelo.setEstaCancelado(dto.isEstaCancelado());
    }

    private VueloDTO toDto(Vuelo vuelo) {
        return new VueloDTO(
                vuelo.getCodigo(),
                vuelo.getOrigenIata(),
                vuelo.getDestinoIata(),
                vuelo.getHoraSalida(),
                vuelo.getHoraLlegada(),
                vuelo.getCapacidadMax(),
                vuelo.isEstaCancelado()
        );
    }
}
