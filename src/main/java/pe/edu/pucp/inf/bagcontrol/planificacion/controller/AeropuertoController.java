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
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class    AeropuertoController {

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
    public ResponseEntity<?> crearAeropuerto(@RequestBody AeropuertoDTO dto) {
        String error = validar(dto, true);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }

        String codigoIata = normalizarCodigo(dto.getCodigoIata());
        if (aeropuertoRepository.existsById(codigoIata)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "El aeropuerto ya existe."));
        }

        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(codigoIata);
        aplicarCamposEditables(aeropuerto, dto);

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(aeropuertoRepository.save(aeropuerto)));
    }

    @PutMapping("/api/aeropuertos/{iata}")
    public ResponseEntity<?> actualizarAeropuerto(
            @PathVariable String iata,
            @RequestBody AeropuertoDTO dto
    ) {
        String error = validar(dto, false);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        return aeropuertoRepository.findById(normalizarCodigo(iata))
                .map(aeropuerto -> {
                    aplicarCamposEditables(aeropuerto, dto);
                    return ResponseEntity.ok(toDto(aeropuertoRepository.save(aeropuerto)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/aeropuertos/{iata}")
    public ResponseEntity<?> eliminarAeropuerto(@PathVariable String iata) {
        String codigoIata = normalizarCodigo(iata);
        if (!aeropuertoRepository.existsById(codigoIata)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "El aeropuerto no existe."));
        }
        boolean tieneVuelos = vueloRepository.findAll().stream()
                .anyMatch(vuelo -> !vuelo.isEstaCancelado()
                        && (codigoIata.equals(vuelo.getOrigenIata())
                        || codigoIata.equals(vuelo.getDestinoIata())));
        if (tieneVuelos) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No se puede eliminar porque tiene vuelos asociados."));
        }
        aeropuertoRepository.deleteById(codigoIata);
        return ResponseEntity.noContent().build();
    }

    private void aplicarCamposEditables(Aeropuerto aeropuerto, AeropuertoDTO dto) {
        aeropuerto.setCiudad(dto.getCiudad().trim());
        aeropuerto.setPais(dto.getPais().trim());
        aeropuerto.setContinente(dto.getContinente().trim());
        aeropuerto.setCapacidadAlmacen(dto.getCapacidadAlmacen());
        aeropuerto.setGmt(dto.getGmt());
        aeropuerto.setLatitud(dto.getLatitud());
        aeropuerto.setLongitud(dto.getLongitud());
    }

    private String validar(AeropuertoDTO dto, boolean validarCodigo) {
        if (dto == null) {
            return "El cuerpo de la solicitud es obligatorio.";
        }
        if (validarCodigo) {
            String codigoIata = normalizarCodigo(dto.getCodigoIata());
            if (codigoIata == null || !codigoIata.matches("[A-Z]{4}")) {
                return "El código IATA debe contener exactamente 4 letras.";
            }
        }
        if (esVacio(dto.getCiudad())) {
            return "La ciudad es obligatoria.";
        }
        if (esVacio(dto.getPais())) {
            return "El país es obligatorio.";
        }
        if (esVacio(dto.getContinente())) {
            return "El continente es obligatorio.";
        }
        if (dto.getGmt() < -12 || dto.getGmt() > 14) {
            return "El GMT debe estar entre -12 y 14.";
        }
        if (dto.getCapacidadAlmacen() <= 0) {
            return "La capacidad debe ser mayor que cero.";
        }
        if (dto.getLatitud() < -90 || dto.getLatitud() > 90) {
            return "La latitud debe estar entre -90 y 90.";
        }
        if (dto.getLongitud() < -180 || dto.getLongitud() > 180) {
            return "La longitud debe estar entre -180 y 180.";
        }
        return null;
    }

    private String normalizarCodigo(String codigoIata) {
        return codigoIata == null ? null : codigoIata.trim().toUpperCase();
    }

    private boolean esVacio(String valor) {
        return valor == null || valor.isBlank();
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
