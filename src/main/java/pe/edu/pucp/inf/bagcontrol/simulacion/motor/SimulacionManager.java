package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SimulacionManager {

    private final PlanificadorService planificadorService;
    private final AeropuertoRepository aeropuertoRepository;
    private final WebSocketPublisher webSocketPublisher;

    private final ConcurrentHashMap<String, SimulacionJob> trabajosActivos = new ConcurrentHashMap<>();

    public String crearJob(LocalDateTime fechaInicio, LocalDateTime fechaFin, int k, String algoritmo) {
        if (fechaInicio == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha inicio es obligatoria.");
        }
        if (k <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El salto k debe ser mayor que cero.");
        }
        String simulacionId = UUID.randomUUID().toString();

        // 1. Instanciamos la memoria y su mutador para este job específico
        SimulacionState state = new SimulacionState(simulacionId);
        SimulacionStateMutator mutator = new SimulacionStateMutator(state, aeropuertoRepository);

        // 2. Determinamos la configuración de colapso según el escenario
        ConfiguracionColapsoDTO configColapso = null;
        if (fechaFin == null) {
            configColapso = crearConfiguracionColapsoPorDefecto();
            state.setModoSimulacion("COLAPSO");
        } else {
            if (fechaInicio.isAfter(fechaFin) || fechaInicio.isEqual(fechaFin)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha fin debe ser mayor a la fecha inicio.");
            }
            state.setModoSimulacion("ESTANDAR");
        }

        // 3. Instanciamos la fábrica de eventos pasándole la configuración
        SimulacionEventosFactory eventosFactory = new SimulacionEventosFactory(configColapso);

        // 4. Armamos el Job con todas sus dependencias
        SimulacionJob job = new SimulacionJob(
                simulacionId,
                fechaInicio,
                fechaFin,
                k,
                algoritmo,
                planificadorService,
                aeropuertoRepository,
                webSocketPublisher,
                eventosFactory,
                state,
                configColapso,
                mutator
        );

        trabajosActivos.put(simulacionId, job);

        return simulacionId;
    }

    public void arrancarJob(String simulacionId) {
        SimulacionJob job = trabajosActivos.get(simulacionId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulación no encontrada: " + simulacionId);
        }
        if (!"CREADA".equals(job.getState().getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La simulacion ya fue arrancada: " + simulacionId);
        }
        Thread thread = new Thread(job, "simulacion-" + simulacionId);
        job.asignarHilo(thread);
        thread.start();
    }

    public String crearYArrancarJob(LocalDateTime fechaInicio, LocalDateTime fechaFin, int k, String algoritmo) {
        String simulacionId = crearJob(fechaInicio, fechaFin, k, algoritmo);
        arrancarJob(simulacionId);
        return simulacionId;
    }

    public String crearYArrancarJobColapso(LocalDateTime fechaInicio, int k, String algoritmo) {
        return crearYArrancarJob(fechaInicio, null, k, algoritmo);
    }

    private ConfiguracionColapsoDTO crearConfiguracionColapsoPorDefecto() {
        double umbralSinItinerario = 0.10;
        double umbralSLA = 0.00;
        double umbralAeropuerto = 1.00;
        return new ConfiguracionColapsoDTO(umbralSinItinerario, umbralSLA, umbralAeropuerto);
    }

    public void detenerJob(String simulacionId) {
        obtenerJob(simulacionId).detener();
    }

    public void pausarJob(String simulacionId) {
        obtenerJob(simulacionId).pausar();
    }

    public void reanudarJob(String simulacionId) {
        obtenerJob(simulacionId).reanudar();
    }

    public SimulacionEstadoDTO obtenerEstado(String simulacionId) {
        SimulacionJob job = obtenerJob(simulacionId);
        SimulacionState state = job.getState();

        return new SimulacionEstadoDTO(
                state.getSimulacionId(),
                state.getEstado(),
                job.estaPausada(),
                job.estaDetenida(),
                job.getSaMs(),
                state.getUltimoLoteEmitidoNumero().get(),
                job.getAlgoritmo(),
                job.getK(),
                job.getFechaInicio().toString(),
                job.getFechaCreacion().toString(),
                state.getTiempoActual() != null ? state.getTiempoActual().toString() : null
        );
    }

    public SimulacionState obtenerState(String simulacionId) {
        return obtenerJob(simulacionId).getState();
    }

    public List<EnvioDTO> extraerEnviosPorVuelo(String simulacionId, Long codigoVuelo) {
        return obtenerState(simulacionId)
                .getEnviosPorVuelo()
                .getOrDefault(codigoVuelo, List.of());
    }

    public SolucionRuta obtenerPlanCompleto(String simulacionId) {
        SolucionRuta solucion = obtenerState(simulacionId).getSolucionActual();
        if (solucion == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El plan aun no ha sido generado.");
        }
        return solucion;
    }

    private SimulacionJob obtenerJob(String simulacionId) {
        SimulacionJob job = trabajosActivos.get(simulacionId);
        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No existe una simulacion activa con id: " + simulacionId
            );
        }
        return job;
    }

    public List<RutaAsignada> obtenerEnviosEnAeropuerto(String simulacionId, String codigoIata) {
        SimulacionState state = obtenerState(simulacionId);

        // 1. Filtrar los IDs de pedidos cuyo último aeropuerto sea el solicitado
        // y que NO hayan sido entregados todavía.
        List<String> idsPedidosEnAeropuerto = state.getUltimoAeropuertoPorEnvio().entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(codigoIata))
                .map(Map.Entry::getKey)
                .filter(idPedido -> !state.getEnviosEntregados().contains(idPedido))
                .toList();

        // 2. Recuperar la RutaAsignada completa desde el mapa de seguimiento
        return idsPedidosEnAeropuerto.stream()
                .map(id -> state.getEnviosEnSeguimiento().get(id))
                .filter(Objects::nonNull)
                .toList();
    }
}
