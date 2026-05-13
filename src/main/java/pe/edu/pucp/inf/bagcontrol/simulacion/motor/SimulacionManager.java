package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SimulacionManager {

    private final PlanificadorService planificadorService;
    private final AeropuertoRepository aeropuertoRepository;
    private final WebSocketPublisher webSocketPublisher;

    private final ConcurrentHashMap<String, SimulacionJob> trabajosActivos = new ConcurrentHashMap<>();

    public String crearJob(LocalDate fechaInicio, LocalDate fechaFin, int k, String algoritmo) {
        String simulacionId = UUID.randomUUID().toString();
        SimulacionJob job;

        if (fechaFin == null) {
            ConfiguracionColapsoDTO configColapso = crearConfiguracionColapsoPorDefecto();
            job = new SimulacionJob(
                    simulacionId, fechaInicio, null ,k, algoritmo, // fechaFin es null
                    planificadorService, aeropuertoRepository, webSocketPublisher, configColapso
            );
        } else {
            if (fechaInicio.isAfter(fechaFin) || fechaInicio.isEqual(fechaFin)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha fin debe ser mayor a la fecha inicio.");
            }
            // Pasamos AMBOS: fechaFin (para saber cuándo parar) y k (tamaño del lote)
            job = new SimulacionJob(
                    simulacionId, fechaInicio, (int) ChronoUnit.DAYS.between(fechaInicio, fechaFin), k, algoritmo,
                    planificadorService, aeropuertoRepository, webSocketPublisher, null // Sin config de colapso
            );
        }
        trabajosActivos.put(simulacionId, job);

        return simulacionId;
    }

    public void arrancarJob(String simulacionId) {
        SimulacionJob job = trabajosActivos.get(simulacionId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulación no encontrada: " + simulacionId);
        }
        Thread thread = new Thread(job, "simulacion-" + simulacionId);
        job.asignarHilo(thread);
        thread.start();
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

    public void cambiarVelocidad(String simulacionId, long saMs) {
        if (saMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La velocidad no puede ser negativa.");
        }
        obtenerJob(simulacionId).cambiarVelocidad(saMs);
    }

    public SimulacionEstadoDTO obtenerEstado(String simulacionId) {
        SimulacionJob job = obtenerJob(simulacionId);
        SimulacionState state = job.getState();

        return new SimulacionEstadoDTO(
                state.getSimulacionId(),
                state.getEstado(),
                job.estaPausada(),
                job.estaDetenida(),
                state.getVelocidadMs(),
                state.getUltimoLoteEmitidoNumero().get(),
                job.getAlgoritmo(),
                job.getK(),
                job.getFechaInicio().toString(),
                job.getFechaCreacion().toString(),
                state.getTiempoSimuladoActual() != null ? state.getTiempoSimuladoActual().toString() : null
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
}
