package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Data;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.MetricasPlanificacionBloque;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.DetalleColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class SimulacionState {
    //Memoria

    //Usado para operaciones con aeropuertos
    private Map<String, Aeropuerto> aeropuertosSnapshot = new ConcurrentHashMap<>();

    //Parece que se confunde con aeropuertosSnpashot -> revisar
    private Map<String, Integer> inventarioSnapshot = new ConcurrentHashMap<>();
    private SolucionRuta solucionActual;

    //Envíos de cada vuelo (generada a partir de la solución del planificador)
    private Map<String, List<EnvioDTO>> enviosPorVuelo = new ConcurrentHashMap<>();
    private LocalDateTime tiempoActual;

    //Envíos que se procesarán en el siguiente salto
    private List<Envio> enviosPendientes = new ArrayList<>();


    private Map<String, RutaAsignada> enviosEnSeguimiento = new ConcurrentHashMap<>();
    private Set<String> enviosRegistrados = ConcurrentHashMap.newKeySet();
    private Set<String> enviosEntregados = ConcurrentHashMap.newKeySet();
    // ETA de la última ruta vigente por envío; permite comparar calidad entre tamaños de bloque.
    private Map<String, Long> minutosEntregaPlanificadaPorEnvio = new ConcurrentHashMap<>();
    private Map<String, String> ultimoAeropuertoPorEnvio = new ConcurrentHashMap<>();
    private Map<String, AsignacionResumen> ultimaAsignacionPorEnvio = new ConcurrentHashMap<>();
    private long bloquesProcesados;
    private long tiempoPlanificacionBloquesMs;
    private long tiempoTotalBloquesMs;
    private long maxTiempoPlanificacionBloqueMs;
    private long maxTiempoTotalBloqueMs;
    private long sumaSaBloquesMs;
    private long sumaDiferenciaSaTaMs;
    private long bloquesTaMayorSa;
    private List<String> historialAjustesSa = new java.util.concurrent.CopyOnWriteArrayList<>();
    private List<MetricasPlanificacionBloque> metricasPlanificacionPorBloque =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    //Simulacion-metadatos
    private final String simulacionId;
    private LocalDateTime fechaInicioSimulacion;
    private int kMinutos;
    private int cicloActual;
    private LoteEventosDTO ultimoLoteEmitido;
    private AtomicLong ultimoLoteEmitidoNumero = new AtomicLong(0);
    private String estado = "CREADA";
    private String modoSimulacion = "NORMAL";
    private Instant fechaHoraInicioReal;
    private Instant fechaHoraFinReal;

    //Colapso
    private MetricasColapsoDTO metricasColapsoActuales;
    private String motivoColapso;
    private DetalleColapsoDTO detalleColapso;

    // --- Historial por lote ---
    private final Map<Long, Map<String, List<EnvioDTO>>> histEnviosPorVuelo = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> histEnviosEntregados = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, String>> histUltimoAeropuertoPorEnvio = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, RutaAsignada>> histEnviosEnSeguimiento = new ConcurrentHashMap<>();
    private final AtomicLong ultimoLoteSnapshot = new AtomicLong(0);

    public SimulacionState(String simulacionId) {
        this.simulacionId = simulacionId;
    }

    public long siguienteLote() {
        return ultimoLoteEmitidoNumero.incrementAndGet();
    }

    public long siguienteLoteSnapshot() {
        return ultimoLoteSnapshot.incrementAndGet();
    }

    public long getUltimoLoteSnapshot() {
        return ultimoLoteSnapshot.get();
    }

    public synchronized void registrarInicioReal() {
        if (fechaHoraInicioReal == null) fechaHoraInicioReal = Instant.now();
    }

    public synchronized void registrarFinReal() {
        if (fechaHoraFinReal == null) fechaHoraFinReal = Instant.now();
    }

    public Aeropuerto obtenerAeropuerto(String idAeropuerto){
        return this.aeropuertosSnapshot.get(idAeropuerto);
    }

    public void guardarSnapshot() {
        long loteNum = siguienteLoteSnapshot();

        histEnviosPorVuelo.put(loteNum, new TreeMap<>(enviosPorVuelo));
        histEnviosEntregados.put(loteNum, new HashSet<>(enviosEntregados));
        histUltimoAeropuertoPorEnvio.put(loteNum, new HashMap<>(ultimoAeropuertoPorEnvio));
        histEnviosEnSeguimiento.put(loteNum, new HashMap<>(enviosEnSeguimiento));

        // Mantener el lote visible, el preparado y dos lotes de margen para reconexión.
        long umbral = loteNum - 4;
        if (umbral > 0) {
            histEnviosPorVuelo.keySet().removeIf(k -> k <= umbral);
            histEnviosEntregados.keySet().removeIf(k -> k <= umbral);
            histUltimoAeropuertoPorEnvio.keySet().removeIf(k -> k <= umbral);
            histEnviosEnSeguimiento.keySet().removeIf(k -> k <= umbral);
        }
    }

    public void registrarTiempoBloque(long planificacionMs, long totalMs, long saMs) {
        tiempoPlanificacionBloquesMs += planificacionMs;
        tiempoTotalBloquesMs += totalMs;
        sumaSaBloquesMs += saMs;
        sumaDiferenciaSaTaMs += saMs - totalMs;
        if (totalMs > saMs) {
            bloquesTaMayorSa++;
        }
        maxTiempoPlanificacionBloqueMs = Math.max(maxTiempoPlanificacionBloqueMs, planificacionMs);
        maxTiempoTotalBloqueMs = Math.max(maxTiempoTotalBloqueMs, totalMs);
    }

}
