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
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloDTO;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class VueloController {

    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;

    @GetMapping("/api/vuelos")
    public List<VueloDTO> listarVuelos() {
        return vueloRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/api/vuelos")
    public ResponseEntity<VueloDTO> crearVuelo(@RequestBody VueloDTO dto) {
        if (!esVueloValido(dto)) {
            return ResponseEntity.badRequest().build();
        }
        Vuelo vuelo = new Vuelo();
        aplicarCampos(vuelo, dto);
        vuelo.setCreadoPorCrud(true);
        Vuelo guardado = vueloRepository.save(vuelo);
        System.out.println("[CRUD-VUELO] creado id=" + guardado.getCodigo()
                + " origen=" + guardado.getOrigenIata()
                + " destino=" + guardado.getDestinoIata());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(guardado));
    }

    @PutMapping("/api/vuelos/{id}")
    public ResponseEntity<VueloDTO> actualizarVuelo(@PathVariable Long id, @RequestBody VueloDTO dto) {
        if (!esVueloValido(dto)) {
            return ResponseEntity.badRequest().build();
        }
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    aplicarCampos(vuelo, dto);
                    vuelo.setCreadoPorCrud(true);
                    Vuelo guardado = vueloRepository.save(vuelo);
                    System.out.println("[CRUD-VUELO] actualizado id=" + guardado.getCodigo());
                    return ResponseEntity.ok(toDto(guardado));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/vuelos/{id}")
    public ResponseEntity<Void> eliminarVuelo(@PathVariable Long id) {
        if (!vueloRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        vueloRepository.deleteById(id);
        System.out.println("[CRUD-VUELO] eliminado id=" + id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/vuelos/{id}/cancelar")
    public ResponseEntity<VueloDTO> cancelarVuelo(@PathVariable Long id) {
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    vuelo.setEstaCancelado(true);
                    Vuelo guardado = vueloRepository.save(vuelo);
                    System.out.println("[CRUD-VUELO] actualizado id=" + guardado.getCodigo());
                    return ResponseEntity.ok(toDto(guardado));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void aplicarCampos(Vuelo vuelo, VueloDTO dto) {
        vuelo.setOrigenIata(dto.getOrigenIata() != null ? dto.getOrigenIata().trim().toUpperCase() : null);
        vuelo.setDestinoIata(dto.getDestinoIata() != null ? dto.getDestinoIata().trim().toUpperCase() : null);
        vuelo.setHoraSalida(dto.getHoraSalida());
        vuelo.setHoraLlegada(dto.getHoraLlegada());
        vuelo.setCapacidadMax(dto.getCapacidadMax());
        vuelo.setEstaCancelado(dto.isEstaCancelado());
    }

    private boolean esVueloValido(VueloDTO dto) {
        if (dto.getOrigenIata() == null || dto.getDestinoIata() == null
                || dto.getHoraSalida() == null || dto.getHoraLlegada() == null) {
            return false;
        }
        String origenIata = dto.getOrigenIata().trim().toUpperCase();
        String destinoIata = dto.getDestinoIata().trim().toUpperCase();
        if (origenIata.equals(destinoIata)) {
            return false;
        }
        if (!aeropuertoRepository.existsById(origenIata) || !aeropuertoRepository.existsById(destinoIata)) {
            return false;
        }
        if (dto.getHoraSalida().equals(dto.getHoraLlegada())) {
            return false;
        }
        return dto.getCapacidadMax() > 0;
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
