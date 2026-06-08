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
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.AeropuertoDTO;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AeropuertoController {

    private final AeropuertoRepository aeropuertoRepository;
    private final VueloRepository vueloRepository;

    @GetMapping("/api/aeropuertos")
    public List<AeropuertoDTO> listarAeropuertos() {
        return aeropuertoRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping("/api/aeropuertos")
    public ResponseEntity<AeropuertoDTO> crearAeropuerto(@RequestBody AeropuertoDTO dto) {
        if (dto.getCodigoIata() == null || dto.getCodigoIata().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (aeropuertoRepository.existsById(dto.getCodigoIata())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(dto.getCodigoIata());
        aplicarCamposEditables(aeropuerto, dto);

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(aeropuertoRepository.save(aeropuerto)));
    }

    @PutMapping("/api/aeropuertos/{iata}")
    public ResponseEntity<AeropuertoDTO> actualizarAeropuerto(
            @PathVariable String iata,
            @RequestBody AeropuertoDTO dto
    ) {
        return aeropuertoRepository.findById(iata)
                .map(aeropuerto -> {
                    aplicarCamposEditables(aeropuerto, dto);
                    return ResponseEntity.ok(toDto(aeropuertoRepository.save(aeropuerto)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/aeropuertos/{iata}")
    public ResponseEntity<?> eliminarAeropuerto(@PathVariable String iata) {
        if (!aeropuertoRepository.existsById(iata)) {
            return ResponseEntity.notFound().build();
        }
        boolean tieneVuelos = vueloRepository.findAll().stream()
                .anyMatch(vuelo -> !vuelo.isEstaCancelado()
                        && (iata.equals(vuelo.getOrigenIata()) || iata.equals(vuelo.getDestinoIata())));
        if (tieneVuelos) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        aeropuertoRepository.deleteById(iata);
        return ResponseEntity.noContent().build();
    }

    private void aplicarCamposEditables(Aeropuerto aeropuerto, AeropuertoDTO dto) {
        aeropuerto.setCiudad(dto.getCiudad());
        aeropuerto.setPais(dto.getPais());
        aeropuerto.setContinente(dto.getContinente());
        aeropuerto.setCapacidadAlmacen(dto.getCapacidadAlmacen());
        aeropuerto.setGmt(dto.getGmt());
        aeropuerto.setLatitud(dto.getLatitud());
        aeropuerto.setLongitud(dto.getLongitud());
    }

    private AeropuertoDTO toDto(Aeropuerto aeropuerto) {
        return new AeropuertoDTO(
                aeropuerto.getCodigoIata(),
                aeropuerto.getCiudad(),
                aeropuerto.getPais(),
                aeropuerto.getContinente(),
                aeropuerto.getCapacidadAlmacen(),
                aeropuerto.getGmt(),
                aeropuerto.getLatitud(),
                aeropuerto.getLongitud(),
                0
        );
    }
}
