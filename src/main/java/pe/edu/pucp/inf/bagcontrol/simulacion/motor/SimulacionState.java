package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.DetalleColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class SimulacionState {
    //Memoria
    private Map<String, Aeropuerto> aeropuertosSnapshot = new ConcurrentHashMap<>();
    private Map<String, Integer> inventarioSnapshot = new ConcurrentHashMap<>();
    private SolucionRuta solucionActual;
    private Map<Long, List<EnvioDTO>> enviosPorVuelo = new ConcurrentHashMap<>();
    private LocalDateTime tiempoActual;
    private List<Envio> enviosPendientes = new ArrayList<>();
    private Map<String, RutaAsignada> enviosEnSeguimiento = new ConcurrentHashMap<>();
    private Set<String> enviosRegistrados = ConcurrentHashMap.newKeySet();
    private Set<String> enviosEntregados = ConcurrentHashMap.newKeySet();
    private Map<String, String> ultimoAeropuertoPorEnvio = new ConcurrentHashMap<>();
    private long bloquesProcesados;

    //Simulacion-metadatos
    private final String simulacionId;
    private int cicloActual;
    private LoteEventosDTO ultimoLoteEmitido;
    private AtomicLong ultimoLoteEmitidoNumero = new AtomicLong(0);
    private String estado = "CREADA";
    private String modoSimulacion = "NORMAL";

    //Colapso
    private MetricasColapsoDTO metricasColapsoActuales;
    private String motivoColapso;
    private DetalleColapsoDTO detalleColapso;

    public SimulacionState(String simulacionId) {
        this.simulacionId = simulacionId;
    }
    public long siguienteLote() {
        return ultimoLoteEmitidoNumero.incrementAndGet();
    }
}
