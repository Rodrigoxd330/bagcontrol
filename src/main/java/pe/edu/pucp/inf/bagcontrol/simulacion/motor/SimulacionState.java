package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class SimulacionState {
    private final String simulacionId;
    private Instant tiempoSimuladoActual;
    private Map<String, Aeropuerto> aeropuertosSnapshot = new ConcurrentHashMap<>();
    private Map<String, Integer> inventarioSnapshot = new ConcurrentHashMap<>();
    private SolucionRuta solucionActual;
    private Map<Long, List<EnvioDTO>> enviosPorVuelo = new ConcurrentHashMap<>();
    private String estado = "CREADA";
    private LoteEventosDTO ultimoLoteEmitido;
    private AtomicLong ultimoLoteEmitidoNumero = new AtomicLong(0);
    private AtomicLong saMs = new AtomicLong(1000);
    private String modoSimulacion = "NORMAL";
    private MetricasColapsoDTO metricasColapsoActuales;
    private String estadoColapso = "NO_EVALUADO";
    private String motivoColapso;
    private int cicloActual;

    public SimulacionState(String simulacionId, long velocidadInicialMs) {
        this.simulacionId = simulacionId;
        this.saMs.set(velocidadInicialMs);
    }

    public long siguienteLote() {
        return ultimoLoteEmitidoNumero.incrementAndGet();
    }

    public long getVelocidadMs() {
        return saMs.get();
    }

    public void cambiarVelocidad(long nuevaVelocidadMs) {
        saMs.set(nuevaVelocidadMs);
    }
}
