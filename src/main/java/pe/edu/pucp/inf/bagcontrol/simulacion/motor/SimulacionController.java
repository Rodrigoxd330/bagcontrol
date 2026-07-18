package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.auth.AuthService;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioAlmacenDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioPorVueloRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioRutaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MaletaSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionActivaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.*;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.out.RespuestaInicioSimulacionDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/simulacion")
@RequiredArgsConstructor
public class SimulacionController {

    private static final String MODO_COLAPSO = "COLAPSO";
    private static final String MODO_NORMAL = "NORMAL";
    private static final String MODO_OPERACION_DIA = "OPERACION_DIA";
    private static final String MODO_BENCHMARK = "BENCHMARK";
    private final WebSocketPublisher publisher;

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
            @RequestParam(value = "k", defaultValue = "60") int k,
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
        // El reloj global de la simulacion trabaja en UTC; el frontend se encarga
        // de convertir a la zona horaria del usuario para mostrar la hora local.
        LocalDateTime ahora = LocalDateTime.now(java.time.ZoneOffset.UTC);
        UsuarioSesion propietario = authService.resolverBearer(authorization);
        String simulacionId = simulacionManager.crearYArrancarJob(
                ahora, null, 1, "TABU", "0", propietario
        );
        String topic = "/topic/simulacion/" + simulacionId + "/eventos";
        return new RespuestaInicioSimulacionDTO(simulacionId, topic, MODO_OPERACION_DIA);
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

    @PostMapping("/{simulacionId}/vuelos/cancelar")
    public Map<String,String> cancelarVuelo(
            @PathVariable String simulacionId,
            @RequestBody EnvioPorVueloRequestDTO request
    ){
        SimulacionState state = simulacionManager.obtenerState(simulacionId);
        List<EnvioDTO> envios = simulacionManager.extraerEnviosPorVuelo(simulacionId, request.getFlight(), request.getTimestamp());
        LoteEventosDTO ultimoLote = state.getUltimoLoteEmitido();
        if (ultimoLote == null || request.getFlight() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Vuelo no encontrado");
        }

        List<EventoBaseDTO> eventos = ultimoLote.getEventos();
        EventoVueloDTO cancelado = null;
        for (int i = 0; i < eventos.size(); i++) {
            EventoBaseDTO evento = eventos.get(i);
            if (!(evento instanceof EventoVueloDTO vuelo)
                    || (vuelo.getTipo() != TipoEvento.VUELO_DESPEGA && vuelo.getTipo() != TipoEvento.VUELO_ATERRIZA)
                    || !Objects.equals(vuelo.claveInstanciaVuelo(), request.getFlight().claveInstanciaVuelo())) {
                continue;
            }
            cancelado = new SimulacionEventosFactory(null)
                    .crearEventoVuelo(vuelo.toVueloInstanciado(), TipoEvento.VUELO_CANCELADO);
            cancelado.setCodigoEnvios(vuelo.getCodigoEnvios() == null ? List.of() : List.copyOf(vuelo.getCodigoEnvios()));
            cancelado.setCantidadMaletas(vuelo.getCantidadMaletas());
            cancelado.setCapacidadMax(vuelo.getCapacidadMax());
            cancelado.setPorcentajeOcupacion(vuelo.getPorcentajeOcupacion());
            eventos.set(i, cancelado);
            break;
        }
        if (cancelado == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Vuelo no encontrado");
        }

        publisher.publicarLote(simulacionId, new LoteEventosDTO(
                simulacionId,
                state.siguienteLote(),
                Instant.now().toString(),
                Instant.now().toString(),
                1,
                List.of(cancelado),
                List.of(),
                0
        ));

        List<Envio> enviosPendientes = new ArrayList<>(state.getEnviosPendientes());
        java.util.Set<String> idsPendientes = enviosPendientes.stream()
                .map(Envio::getIdPedido)
                .collect(java.util.stream.Collectors.toSet());
        envios.stream()
                .filter(dto -> idsPendientes.add(dto.getIdPedido()))
                .map(dto -> new Envio(
                        dto.getIdPedido(), dto.getOrigenIata(), dto.getDestinoIata(),
                        LocalDateTime.parse(dto.getFechaHora()), dto.getCantidadMaletas(), dto.getIdCliente(),
                        true, dto.isEsOperacionDia(), false
                ))
                .forEach(enviosPendientes::add);
        state.setEnviosPendientes(enviosPendientes);
        return Map.of("mensaje", "Vuelo cancelado");
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
