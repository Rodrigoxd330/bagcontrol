package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.auth.AuthService;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloResponseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioAlmacenDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioPorVueloRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioRutaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MaletaSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionActivaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.VueloCancelableDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.*;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.out.RespuestaInicioSimulacionDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulacion")
@RequiredArgsConstructor
public class SimulacionController {

    private static final String MODO_COLAPSO = "COLAPSO";
    private static final String MODO_NORMAL = "NORMAL";
    private static final String MODO_OPERACION_DIA = "OPERACION_DIA";
    private static final String MODO_BENCHMARK = "BENCHMARK";
    private final SimulacionManager simulacionManager;
    private final AuthService authService;

    /*
    * Crear el hilo (sin ejecutarlo) para que esté 'listo para arrncar'
    * devuelve el id de la simulación
     */
    @PostMapping("/preparar")
    public RespuestaInicioSimulacionDTO preparaSimulacion(
            @RequestParam("fechaInicio") String fechaInicio,
            @RequestParam(value = "fechaFin", required = false) String fechaFin,
            @RequestParam(value = "k", defaultValue = "120") int k,
            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo,
            @RequestParam(value = "modo", required = false) String modo,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        long t0 = System.currentTimeMillis();


        // El helper ahora devuelve el LocalDateTime correcto interpretando el estándar internacional
        LocalDateTime inicio = parseFechaHoraFlexible(fechaInicio);
        LocalDateTime fin = parseFechaHoraFlexible(fechaFin);
        UsuarioSesion propietario = authService.resolverBearer(authorization);

        String simulacionId = simulacionManager.crearJob(inicio, fin, k, algoritmo, modo, propietario);
        System.out.println("[BACK-SIM-TIME] simulacion creada id=" + simulacionId
                + " elapsedMs=" + (System.currentTimeMillis() - t0));
        String modo_final = "0".equals(modo) ? MODO_OPERACION_DIA
                : MODO_BENCHMARK.equalsIgnoreCase(modo) ? MODO_BENCHMARK
                : (fin == null) ? MODO_COLAPSO : MODO_NORMAL;
        String topic = "/topic/simulacion/" + simulacionId + "/eventos";

        return new RespuestaInicioSimulacionDTO(simulacionId, topic, modo_final);
    }

    @GetMapping("/activas")
    public List<SimulacionActivaDTO> listarActivas(
            @RequestParam(value = "modo", required = false) String modo
    ) {
        return simulacionManager.listarActivas(modo);
    }

    @GetMapping("/operacion-dia/activa")
    public ResponseEntity<SimulacionActivaDTO> obtenerOperacionDiaActiva() {
        return simulacionManager.obtenerOperacionDiaActivaDTO()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/operacion-dia/iniciar")
    public RespuestaInicioSimulacionDTO iniciarOperacionDia(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        try{
            // El reloj global de la simulacion trabaja en UTC; el frontend se encarga
            // de convertir a la zona horaria del usuario para mostrar la hora local.
            LocalDateTime ahora = LocalDateTime.now(java.time.ZoneOffset.UTC);
            UsuarioSesion propietario = authService.resolverBearer(authorization);
            String simulacionId = simulacionManager.crearYArrancarJob(
                    ahora, null, 1, "TABU", "0", propietario
            );
            String topic = "/topic/simulacion/" + simulacionId + "/eventos";
            return new RespuestaInicioSimulacionDTO(simulacionId, topic, MODO_OPERACION_DIA);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private LocalDateTime parseFechaHoraFlexible(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        String limpio = valor.trim();
        try {
            // Si el string contiene la 'Z' o un desvío de zona horaria, es un formato internacional válido.
            // Lo parseamos como Instant y lo convertimos a LocalDateTime en la línea temporal de UTC.
            if (limpio.contains("Z") || limpio.contains("z") || limpio.contains("+") || (limpio.lastIndexOf("-") > 10)) {
                return LocalDateTime.ofInstant(Instant.parse(limpio), ZoneOffset.UTC);
            }

            // Fallback: Si por alguna razón llega un formato local puro (ej. YYYY-MM-DDTHH:mm), se usa el parse estándar
            return LocalDateTime.parse(limpio);
        } catch (DateTimeParseException e) {
            try {
                // Segundo fallback: Si solo enviaron la fecha plana (ej. YYYY-MM-DD), se asume el inicio del día
                return java.time.LocalDate.parse(limpio).atStartOfDay();
            } catch (DateTimeParseException ex) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "No se pudo parsear la estructura de fecha provista: " + valor
                );
            }
        }
    }
    /*
    * Solamante arranca la simulación del id que le pasemos
     */
    @PostMapping("/iniciar/{simulacionId}/arrancar")
    public Map<String, String> arrancarSimulacion(@PathVariable String simulacionId) {
        long t0 = System.currentTimeMillis();
        System.out.println("[BACK-SIM-TIME] arrancar recibido id=" + simulacionId
                + " ts=" + java.time.Instant.now());
        simulacionManager.arrancarJob(simulacionId);
        System.out.println("[BACK-SIM-TIME] job iniciado id=" + simulacionId
                + " elapsedMs=" + (System.currentTimeMillis() - t0));
        return Map.of("mensaje", "Simulacion en marcha");
    }


    @PostMapping("/{simulacionId}/pausar")
    public Map<String, String> pausar(@PathVariable String simulacionId) {
        simulacionManager.pausarJob(simulacionId);
        return Map.of("estado", "PAUSADA", "mensaje", "Simulacion pausada");
    }

    @PostMapping("/{simulacionId}/reanudar")
    public Map<String, String> reanudar(@PathVariable String simulacionId) {
        simulacionManager.reanudarJob(simulacionId);
        return Map.of("estado", "EN_PROCESO", "mensaje", "Simulacion reanudada");
    }

    @PostMapping("/{simulacionId}/detener")
    public Map<String, String> detener(@PathVariable String simulacionId) {
        simulacionManager.detenerJob(simulacionId);
        return Map.of("estado", "DETENIDA", "mensaje", "Simulacion abortada");
    }

    @GetMapping("/{simulacionId}/estado")
    public SimulacionEstadoDTO obtenerEstado(@PathVariable String simulacionId) {
        return simulacionManager.obtenerEstado(simulacionId);
    }

    @GetMapping("/{simulacionId}/snapshot")
    public LoteEventosDTO obtenerSnapshot(@PathVariable String simulacionId) {
        return simulacionManager.obtenerSnapshotActual(simulacionId);
    }

    /*
     * Puede servir para después
    */
    @PostMapping("/{simulacionId}/vuelos/envios")
    public List<EnvioDTO> obtenerEnviosPorVuelo(
            @PathVariable String simulacionId,
            @RequestBody EnvioPorVueloRequestDTO request
            ) {
        List<EnvioDTO> envios = simulacionManager.extraerEnviosPorVuelo(simulacionId, request.getFlight(), request.getTimestamp());
        return envios;
    }

    @GetMapping("/{simulacionId}/vuelos/cancelables")
    public List<VueloCancelableDTO> listarVuelosCancelables(
            @PathVariable String simulacionId,
            @RequestParam("instanteSimulado") String instanteSimulado
    ) {
        return simulacionManager.listarVuelosCancelables(simulacionId, parseInstant(instanteSimulado));
    }

    @PostMapping("/{simulacionId}/vuelos/{codigoVuelo}/cancelaciones")
    public CancelacionVueloResponseDTO cancelarProximaOcurrencia(
            @PathVariable String simulacionId,
            @PathVariable Long codigoVuelo,
            @RequestBody CancelacionVueloRequestDTO request
    ) {
        try {
            return simulacionManager.cancelarProximaOcurrencia(
                    simulacionId, codigoVuelo, parseInstant(request.getInstanteSimulado()), request.getMotivo()
            );
        } catch (IllegalArgumentException ex) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private Instant parseInstant(String valor) {
        try {
            return Instant.parse(valor);
        } catch (RuntimeException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El instante simulado debe usar formato ISO-8601 UTC"
            );
        }
    }

    @GetMapping("/{simulacionId}/envios/{idPedido}/ruta")
    public EnvioRutaDTO obtenerRutaEnvio(
            @PathVariable String simulacionId,
            @PathVariable String idPedido,
            @RequestParam("timestamp") String timestamp
    ) {
        return simulacionManager.obtenerRutaEnvio(simulacionId, idPedido, timestamp);
    }

    @GetMapping("/{simulacionId}/aeropuertos/{codigoIata}/maletas")
    public List<MaletaSimulacionDTO> obtenerMaletasPorAeropuerto(
            @PathVariable String simulacionId,
            @PathVariable String codigoIata,
            @RequestParam("timestamp") String timestamp
    ) {
        return simulacionManager.obtenerMaletasPorAeropuerto(simulacionId, codigoIata, timestamp);
    }

    @GetMapping("/{simulacionId}/aeropuertos/{codigoIata}/envios")
    public List<EnvioAlmacenDTO> obtenerEnviosPorAlmacen(
            @PathVariable String simulacionId,
            @PathVariable String codigoIata,
            @RequestParam("timestamp") String timestamp
    ) {
        return simulacionManager.obtenerEnviosPorAlmacen(simulacionId, codigoIata, timestamp);
    }
}
