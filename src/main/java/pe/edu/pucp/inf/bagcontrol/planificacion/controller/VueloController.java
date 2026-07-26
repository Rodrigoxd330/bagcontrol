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
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.SimulacionManager;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class VueloController {

    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final SimulacionManager simulacionManager;

    @GetMapping("/api/vuelos")
    public List<VueloDTO> listarVuelos() {
        return vueloRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/api/vuelos")
    public ResponseEntity<?> crearVuelo(@RequestBody VueloDTO dto) {
        String error = validarVuelo(dto);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        Vuelo vuelo = new Vuelo();
        aplicarCampos(vuelo, dto);
        vuelo.setCreadoPorCrud(true);
        Vuelo guardado = vueloRepository.save(vuelo);
        simulacionManager.refrescarCatalogoOperacionDia();
        System.out.println("[CRUD-VUELO] creado id=" + guardado.getCodigo()
                + " origen=" + guardado.getOrigenIata()
                + " destino=" + guardado.getDestinoIata());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(guardado));
    }

    @PutMapping("/api/vuelos/{id}")
    public ResponseEntity<?> actualizarVuelo(@PathVariable Long id, @RequestBody VueloDTO dto) {
        String error = validarVuelo(dto);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    aplicarCampos(vuelo, dto);
                    vuelo.setCreadoPorCrud(true);
                    Vuelo guardado = vueloRepository.save(vuelo);
                    simulacionManager.refrescarCatalogoOperacionDia();
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
        simulacionManager.refrescarCatalogoOperacionDia();
        System.out.println("[CRUD-VUELO] eliminado id=" + id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/vuelos/{id}/cancelar")
    public ResponseEntity<VueloDTO> cancelarVuelo(@PathVariable Long id) {
        return vueloRepository.findById(id)
                .map(vuelo -> {
                    vuelo.setEstaCancelado(true);
                    Vuelo guardado = vueloRepository.save(vuelo);
                    simulacionManager.refrescarCatalogoOperacionDia();
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

    private String validarVuelo(VueloDTO dto) {
        if (dto == null || dto.getOrigenIata() == null || dto.getDestinoIata() == null
                || dto.getHoraSalida() == null || dto.getHoraLlegada() == null) {
            return "Origen, destino, hora de salida y hora de llegada son obligatorios.";
        }
        String origenIata = dto.getOrigenIata().trim().toUpperCase();
        String destinoIata = dto.getDestinoIata().trim().toUpperCase();
        if (!origenIata.matches("[A-Z]{4}") || !destinoIata.matches("[A-Z]{4}")) {
            return "Origen y destino deben ser códigos IATA de 4 letras.";
        }
        if (origenIata.equals(destinoIata)) {
            return "El aeropuerto de origen y destino no pueden ser iguales.";
        }
        Aeropuerto origen = aeropuertoRepository.findById(origenIata).orElse(null);
        if (origen == null) {
            return "No existe el aeropuerto origen " + origenIata + ".";
        }
        Aeropuerto destino = aeropuertoRepository.findById(destinoIata).orElse(null);
        if (destino == null) {
            return "No existe el aeropuerto destino " + destinoIata + ".";
        }
        String errorOrigen = validarAeropuertoParaVuelo(origen, "origen");
        if (errorOrigen != null) {
            return errorOrigen;
        }
        String errorDestino = validarAeropuertoParaVuelo(destino, "destino");
        if (errorDestino != null) {
            return errorDestino;
        }
        if (dto.getHoraSalida().equals(dto.getHoraLlegada())) {
            return "La hora de salida y llegada no pueden ser iguales.";
        }
        if (dto.getCapacidadMax() <= 0) {
            return "La capacidad máxima debe ser mayor que cero.";
        }
        return null;
    }

    private String validarAeropuertoParaVuelo(Aeropuerto aeropuerto, String tipo) {
        if (aeropuerto.getCiudad() == null || aeropuerto.getCiudad().isBlank()
                || aeropuerto.getPais() == null || aeropuerto.getPais().isBlank()
                || aeropuerto.getContinente() == null || aeropuerto.getContinente().isBlank()
                || aeropuerto.getCapacidadAlmacen() <= 0
                || aeropuerto.getGmt() < -12 || aeropuerto.getGmt() > 14
                || aeropuerto.getLatitud() < -90 || aeropuerto.getLatitud() > 90
                || aeropuerto.getLongitud() < -180 || aeropuerto.getLongitud() > 180) {
            return "El aeropuerto " + tipo + " " + aeropuerto.getCodigoIata()
                    + " no tiene datos válidos para la simulación.";
        }
        return null;
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
