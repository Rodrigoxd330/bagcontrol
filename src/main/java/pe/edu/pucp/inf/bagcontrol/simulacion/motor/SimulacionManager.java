package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.Incidencia;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioAlmacenDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioRutaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EscalaRutaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MaletaSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionActivaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SimulacionManager {

    private static final String MODO_OPERACION_DIA = "0";
    private static final String MODO_SIM5D = "1";
    private static final String MODO_COLAPSO_OPERATIVO = "2";
    private static final long MAX_HORIZONTE_OPERACION_HORAS = 48;

    private final PlanificadorService planificadorService;
    private final AeropuertoRepository aeropuertoRepository;
    private final WebSocketPublisher webSocketPublisher;

    private final ConcurrentHashMap<String, SimulacionJob> trabajosActivos = new ConcurrentHashMap<>();

    public String crearJob(LocalDateTime fechaInicio, LocalDateTime fechaFin, int k, String algoritmo, String modo) {
        return crearJob(fechaInicio, fechaFin, k, algoritmo, modo, null);
    }

    public String crearJob(
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin,
            int k,
            String algoritmo,
            String modo,
            UsuarioSesion propietario
    ) {
        if (fechaInicio == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha inicio es obligatoria.");
        }
        if (k <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El salto k debe ser mayor que cero.");
        }
        String modoNormalizado = normalizarModo(modo, fechaFin);
        if (MODO_OPERACION_DIA.equals(modoNormalizado)) {
            Optional<SimulacionJob> operacionExistente = obtenerOperacionDiaActiva();
            if (operacionExistente.isPresent()) {
                return operacionExistente.get().getState().getSimulacionId();
            }
        }
        LocalDateTime fechaFinNormalizada = normalizarFechaFin(fechaInicio, fechaFin, modoNormalizado);
        if (!MODO_COLAPSO_OPERATIVO.equals(modoNormalizado) && fechaFinNormalizada == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha fin es obligatoria para este modo.");
        }
        validarHorizonte(fechaInicio, fechaFinNormalizada, modoNormalizado);
        String simulacionId = UUID.randomUUID().toString();

        // 1. Instanciamos la memoria y su mutador para este job específico
        SimulacionState state = new SimulacionState(simulacionId);
        state.setFechaInicioSimulacion(fechaInicio);
        state.setKMinutos(k);
        SimulacionStateMutator mutator = new SimulacionStateMutator(state, aeropuertoRepository);

        // 2. Determinamos la configuración de colapso según el escenario
        ConfiguracionColapsoDTO configColapso = null;
        if (MODO_COLAPSO_OPERATIVO.equals(modoNormalizado)) {
            configColapso = crearConfiguracionColapsoPorDefecto();
            state.setModoSimulacion("COLAPSO");
        } else {
            if (fechaInicio.isAfter(fechaFinNormalizada) || fechaInicio.isEqual(fechaFinNormalizada)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha fin debe ser mayor a la fecha inicio.");
            }
            state.setModoSimulacion(MODO_OPERACION_DIA.equals(modoNormalizado) ? "OPERACION_DIA" : "ESTANDAR");
        }

        Set<String> enviosCrudExistentes = MODO_OPERACION_DIA.equals(modoNormalizado)
                ? planificadorService.obtenerIdsEnviosCrudActivos()
                : Set.of();
        registrarConfiguracionModo(fechaInicio, fechaFinNormalizada, modoNormalizado, enviosCrudExistentes.size());
        SimulacionContextoDatos contextoDatos = crearContextoDatosSnapshot();

        // 3. Instanciamos la fábrica de eventos pasándole la configuración
        SimulacionEventosFactory eventosFactory = new SimulacionEventosFactory(configColapso);

        // 4. Armamos el Job con todas sus dependencias
        SimulacionJob job = new SimulacionJob(
                simulacionId,
                fechaInicio,
                fechaFinNormalizada,
                k,
                algoritmo,
                planificadorService,
                aeropuertoRepository,
                webSocketPublisher,
                eventosFactory,
                state,
                configColapso,
                mutator,
                modoNormalizado,
                enviosCrudExistentes,
                contextoDatos,
                propietario
        );

        trabajosActivos.put(simulacionId, job);

        return simulacionId;
    }

    public void arrancarJob(String simulacionId) {
        SimulacionJob job = trabajosActivos.get(simulacionId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulación no encontrada: " + simulacionId);
        }
        synchronized (job) {
            if (!"CREADA".equals(job.getState().getEstado())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "La simulacion ya fue arrancada: " + simulacionId);
            }
            refrescarCatalogosMaestros(job);
            job.getState().registrarInicioReal();
            job.getState().setEstado("EN_PROCESO");
            Thread thread = new Thread(job, "simulacion-" + simulacionId);
            job.asignarHilo(thread);
            thread.start();
            System.out.println("[BACK-SIM-TIME] job iniciado id=" + simulacionId
                    + " thread=" + thread.getName()
                    + " ts=" + java.time.Instant.now());
        }
    }

    public String crearYArrancarJob(LocalDateTime fechaInicio, LocalDateTime fechaFin, int k, String algoritmo, String modo) {
        String simulacionId = crearJob(fechaInicio, fechaFin, k, algoritmo, modo);
        arrancarJob(simulacionId);
        return simulacionId;
    }

    public String crearYArrancarJob(
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin,
            int k,
            String algoritmo,
            String modo,
            UsuarioSesion propietario
    ) {
        String simulacionId = crearJob(fechaInicio, fechaFin, k, algoritmo, modo, propietario);
        arrancarJob(simulacionId);
        return simulacionId;
    }

    public Optional<SimulacionActivaDTO> obtenerOperacionDiaActivaDTO() {
        return obtenerOperacionDiaActiva()
                .map(this::crearSimulacionActivaDTO);
    }

    public List<SimulacionActivaDTO> listarActivas(String modo) {
        String modoNormalizado = modo == null || modo.isBlank() ? null : normalizarModo(modo, null);
        return trabajosActivos.values().stream()
                .filter(job -> !esEstadoTerminal(job.getState().getEstado()))
                .filter(job -> modoNormalizado == null || modoNormalizado.equals(job.getModo()))
                .sorted(Comparator.comparing(SimulacionJob::getFechaCreacion).reversed())
                .map(this::crearSimulacionActivaDTO)
                .toList();
    }

    private Optional<SimulacionJob> obtenerOperacionDiaActiva() {
        return trabajosActivos.values().stream()
                .filter(job -> MODO_OPERACION_DIA.equals(job.getModo()))
                .filter(job -> !esEstadoTerminal(job.getState().getEstado()))
                .findFirst();
    }

    private boolean esEstadoTerminal(String estado) {
        return "FINALIZADA".equals(estado)
                || "DETENIDA".equals(estado)
                || "COLAPSADA".equals(estado)
                || "ERROR".equals(estado);
    }

    private SimulacionActivaDTO crearSimulacionActivaDTO(SimulacionJob job) {
        UsuarioSesion propietario = job.getPropietario();
        return new SimulacionActivaDTO(
                job.getState().getSimulacionId(),
                "/topic/simulacion/" + job.getState().getSimulacionId() + "/eventos",
                job.getModo(),
                job.getState().getEstado(),
                job.getK(),
                job.getFechaInicio().toString(),
                job.getFechaCreacion().toString(),
                propietario != null ? propietario.email() : null,
                propietario != null ? propietario.nombre() : null
        );
    }

    private ConfiguracionColapsoDTO crearConfiguracionColapsoPorDefecto() {
        double umbralSinItinerario = 0.10;
        double umbralSLA = 0.00;
        double umbralAeropuerto = 1.00;
        return new ConfiguracionColapsoDTO(umbralSinItinerario, umbralSLA, umbralAeropuerto);
    }

    private SimulacionContextoDatos crearContextoDatosSnapshot() {
        return new SimulacionContextoDatos(
                planificadorService.obtenerVuelosBaseSnapshot().stream().map(this::copiarVuelo).toList(),
                planificadorService.obtenerAeropuertosSnapshot().stream().map(this::copiarAeropuerto).toList(),
                planificadorService.obtenerIncidenciasSnapshot().stream().map(this::copiarIncidencia).toList(),
                Instant.now()
        );
    }

    void refrescarCatalogosMaestros(SimulacionJob job) {
        SimulacionContextoDatos contextoActual = crearContextoDatosSnapshot();
        job.refrescarContextoDatos(contextoActual);
        registrarAuditoriaCatalogo(contextoActual);
    }

    private void registrarAuditoriaCatalogo(SimulacionContextoDatos contexto) {
        Set<String> codigosCrud = contexto.vuelos().stream()
                .filter(Vuelo::isCreadoPorCrud)
                .flatMap(vuelo -> java.util.stream.Stream.of(vuelo.getOrigenIata(), vuelo.getDestinoIata()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> catalogo = contexto.aeropuertos().stream()
                .map(Aeropuerto::getCodigoIata)
                .collect(Collectors.toSet());
        for (String codigo : codigosCrud) {
            boolean existe = catalogo.contains(codigo);
            System.out.println("[AEROPUERTO-CATALOGO-AUDIT] codigo=" + codigo
                    + " existeRepositorio=" + aeropuertoRepository.existsById(codigo)
                    + " existeServicioCrud=" + aeropuertoRepository.existsById(codigo)
                    + " existeCache=false existeCatalogoSimulacion=" + existe
                    + " existeSnapshot=" + existe + " activo=true"
                    + " fuenteUsadaPorVueloFactory=SNAPSHOT_REPOSITORIO_AL_ARRANCAR");
        }
    }

    private Vuelo copiarVuelo(Vuelo origen) {
        Vuelo copia = new Vuelo();
        copia.setCodigo(origen.getCodigo());
        copia.setOrigenIata(origen.getOrigenIata());
        copia.setDestinoIata(origen.getDestinoIata());
        copia.setHoraSalida(origen.getHoraSalida());
        copia.setHoraLlegada(origen.getHoraLlegada());
        copia.setCapacidadMax(origen.getCapacidadMax());
        copia.setEstaCancelado(origen.isEstaCancelado());
        copia.setCreadoPorCrud(origen.isCreadoPorCrud());
        return copia;
    }

    private Aeropuerto copiarAeropuerto(Aeropuerto origen) {
        Aeropuerto copia = new Aeropuerto();
        copia.setCodigoIata(origen.getCodigoIata());
        copia.setCiudad(origen.getCiudad());
        copia.setPais(origen.getPais());
        copia.setContinente(origen.getContinente());
        copia.setGmt(origen.getGmt());
        copia.setCapacidadAlmacen(origen.getCapacidadAlmacen());
        copia.setLatitud(origen.getLatitud());
        copia.setLongitud(origen.getLongitud());
        return copia;
    }

    private Incidencia copiarIncidencia(Incidencia origen) {
        Incidencia copia = new Incidencia();
        copia.setId(origen.getId());
        copia.setFechaHora(origen.getFechaHora());
        copia.setDescripcion(origen.getDescripcion());
        copia.setOrigenIata(origen.getOrigenIata());
        copia.setNoPuedeRecibir(origen.isNoPuedeRecibir());
        copia.setNoPuedeEnviar(origen.isNoPuedeEnviar());
        copia.setTiempoRecuperacionMinutos(origen.getTiempoRecuperacionMinutos());
        return copia;
    }

    private String normalizarModo(String modo, LocalDateTime fechaFin) {
        if (MODO_OPERACION_DIA.equals(modo) || MODO_SIM5D.equals(modo) || MODO_COLAPSO_OPERATIVO.equals(modo)) {
            return modo;
        }
        return fechaFin == null ? MODO_COLAPSO_OPERATIVO : MODO_SIM5D;
    }

    private LocalDateTime normalizarFechaFin(LocalDateTime fechaInicio, LocalDateTime fechaFin, String modo) {
        if (MODO_OPERACION_DIA.equals(modo) && fechaFin == null) {
            return fechaInicio.plusDays(1);
        }
        if (MODO_COLAPSO_OPERATIVO.equals(modo)) {
            return null;
        }
        return fechaFin;
    }

    private void validarHorizonte(LocalDateTime fechaInicio, LocalDateTime fechaFin, String modo) {
        if (!MODO_OPERACION_DIA.equals(modo) || fechaFin == null) {
            return;
        }
        Duration horizonte = Duration.between(fechaInicio, fechaFin);
        if (horizonte.toMinutes() > MAX_HORIZONTE_OPERACION_HORAS * 60) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La operacion dia a dia no puede planificar mas de 2 dias."
            );
        }
    }

    private void registrarConfiguracionModo(
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin,
            String modo,
            int enviosCrudExistentes
    ) {
        if (MODO_OPERACION_DIA.equals(modo)) {
            System.out.println("[OPERACION-DIA] modo=OPERACION_DIA");
            System.out.println("[OPERACION-DIA] enviosIniciales=0");
            System.out.println("[OPERACION-DIA] enviosCrudExcluidos=" + enviosCrudExistentes);
            System.out.println("[OPERACION-DIA] usaZip=false");
            System.out.println("[OPERACION-DIA] horizonteHoras=" + Duration.between(fechaInicio, fechaFin).toHours());
            return;
        }
        if (MODO_COLAPSO_OPERATIVO.equals(modo)) {
            System.out.println("[SIMULACION-CONFIG] modo=COLAPSO usaZip=true");
            return;
        }
        System.out.println("[SIM5D-CONFIG] modo=SIM5D usaZip=true");
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
                state.getTiempoActual() != null ? state.getTiempoActual().toString() : null,
                state.getFechaHoraInicioReal() != null ? state.getFechaHoraInicioReal().toString() : null,
                state.getFechaHoraFinReal() != null ? state.getFechaHoraFinReal().toString() : null
        );
    }

    public LoteEventosDTO obtenerSnapshotActual(String simulacionId) {
        LoteEventosDTO lote = obtenerJob(simulacionId).getState().getUltimoLoteEmitido();
        if (lote == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La simulacion aun no emitio lotes.");
        }
        return lote;
    }

    public SimulacionState obtenerState(String simulacionId) {
        return obtenerJob(simulacionId).getState();
    }

    public List<EnvioDTO> extraerEnviosPorVuelo(String simulacionId, EventoVueloDTO vuelo, String timestamp) {
        SimulacionState state = obtenerState(simulacionId);
        long lote = calcularLoteSnapshot(state, timestamp);
        //Map<Long, List<EnvioDTO>> enviosEnLote = state.getHistEnviosPorVuelo().get(lote);
        Map<String, List<EnvioDTO>> enviosEnLote = new LinkedHashMap<>(state.getEnviosPorVuelo());
        //NOTA: Si solo se consiguen los vuelos del ultimo lote, los vuelos planificados el lote anterior
        // que aun no salen, no se veran bien representados
        //state.getHistEnviosPorVuelo().entrySet().forEach(entry -> {
        //   entry.getValue().entrySet().forEach(_entry -> {
        //       List<EnvioDTO> envios = new ArrayList<>(enviosEnLote.computeIfAbsent(_entry.getKey(),k ->List.of()));
        //       envios.addAll(_entry.getValue());
        //       enviosEnLote.put(_entry.getKey(),envios);
        //   });
        //});
        if (enviosEnLote == null) return List.of();
        String key = vuelo.claveInstanciaVuelo();
        if (!enviosEnLote.containsKey(key) && vuelo.getCantidadMaletas() > 0) {
            System.out.println("[MAP-KEY-MISSING] mapa=enviosPorVuelo key=" + key
                    + " idSimulacion=" + simulacionId
                    + " lote=" + lote
                    + " vuelo=" + vuelo.getCodigoVuelo()
                    + " origen=" + vuelo.getOrigenIata()
                    + " destino=" + vuelo.getDestinoIata()
                    + " cantidadMaletas=" + vuelo.getCantidadMaletas());
        }
        return enviosEnLote.getOrDefault(key, List.of());
    }

    public SolucionRuta obtenerPlanCompleto(String simulacionId) {
        SolucionRuta solucion = obtenerState(simulacionId).getSolucionActual();
        if (solucion == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El plan aun no ha sido generado.");
        }
        return solucion;
    }

    public EnvioRutaDTO obtenerRutaEnvio(String simulacionId, String idPedido, String timestamp) {
        SimulacionState state = obtenerState(simulacionId);
        long lote = calcularLoteSnapshot(state, timestamp);

        Map<String, RutaAsignada> histSeguimiento = state.getHistEnviosEnSeguimiento().get(lote);
        Set<String> histEntregados = state.getHistEnviosEntregados().get(lote);
        Map<String, String> histUltimoAeropuerto = state.getHistUltimoAeropuertoPorEnvio().get(lote);

        if (histSeguimiento == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No hay datos historicos para el lote " + lote + " de la simulacion " + simulacionId);
        }

        RutaAsignada asignacion = histSeguimiento.get(idPedido);
        if (asignacion == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No existe el envio en seguimiento: " + idPedido + " en el lote " + lote);
        }

        boolean entregado = histEntregados != null && histEntregados.contains(idPedido);
        String estado = entregado ? "ENTREGADO"
                : asignacion.getItinerario() == null ? "SIN_ITINERARIO" : "EN_TRANSITO";
        List<EscalaRutaDTO> escalas = asignacion.getItinerario() == null
                ? List.of()
                : asignacion.getItinerario().getVuelos().stream()
                        .map(this::crearEscalaRuta)
                        .toList();
        String aeropuertoActual = histUltimoAeropuerto != null
                ? histUltimoAeropuerto.get(idPedido) : null;

        return new EnvioRutaDTO(
                crearEnvioDTO(asignacion.getEnvio()),
                estado,
                aeropuertoActual,
                asignacion.getItinerario() != null ? asignacion.getItinerario().getIdItinerario() : null,
                escalas
        );
    }

    public List<MaletaSimulacionDTO> obtenerMaletasPorAeropuerto(String simulacionId, String codigoAeropuerto, String timestamp) {
        List<EnvioAlmacenDTO> envios = obtenerEnviosPorAlmacen(simulacionId, codigoAeropuerto, timestamp);
        List<MaletaSimulacionDTO> maletas = new java.util.ArrayList<>();
        for (EnvioAlmacenDTO envioAlmacen : envios) {
            pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO envio = envioAlmacen.getEnvio();
            int total = envio.getCantidadMaletas();
            for (int i = 1; i <= total; i++) {
                maletas.add(new MaletaSimulacionDTO(
                        envio.getIdPedido() + "-M" + i,
                        envio.getIdPedido(),
                        envio.getOrigenIata(),
                        envio.getDestinoIata(),
                        envioAlmacen.getTipoAlmacen(),
                        envioAlmacen.getEstadoEnvio(),
                        total
                ));
            }
        }
        return maletas;
    }

    public List<EnvioAlmacenDTO> obtenerEnviosPorAlmacen(String simulacionId, String codigoAeropuerto, String timestamp) {
        SimulacionState state = obtenerState(simulacionId);
        long lote = calcularLoteSnapshot(state, timestamp);

        Map<String, RutaAsignada> histSeguimiento = state.getHistEnviosEnSeguimiento().get(lote);
        Map<String, String> histUltimoAeropuerto = state.getHistUltimoAeropuertoPorEnvio().get(lote);
        Set<String> histEntregados = state.getHistEnviosEntregados().get(lote);
        final Set<String> entregadosFinal = histEntregados != null ? histEntregados : Set.of();

        if (histSeguimiento == null || histUltimoAeropuerto == null) return List.of();

        return histSeguimiento.values().stream()
                .filter(asignacion -> codigoAeropuerto.equals(
                        histUltimoAeropuerto.get(asignacion.getEnvio().getIdPedido())
                ))
                .sorted(Comparator.comparing(asignacion -> asignacion.getEnvio().getIdPedido()))
                .map(asignacion -> {
                    Envio envio = asignacion.getEnvio();
                    String estado = entregadosFinal.contains(envio.getIdPedido())
                            ? "ENTREGADO"
                            : asignacion.getItinerario() == null ? "SIN_ITINERARIO" : "EN_ALMACEN";
                    String tipoAlmacen = codigoAeropuerto.equals(envio.getDestinoIata()) ? "DESTINO_FINAL" : "TRANSITO";
                    return new EnvioAlmacenDTO(crearEnvioDTO(envio), codigoAeropuerto, tipoAlmacen, estado);
                })
                .toList();
    }

    private EnvioAlmacenDTO crearEnvioAlmacenDTO(
            SimulacionState state,
            String codigoAeropuerto,
            RutaAsignada asignacion
    ) {
        Envio envio = asignacion.getEnvio();
        String estado = state.getEnviosEntregados().contains(envio.getIdPedido())
                ? "ENTREGADO"
                : asignacion.getItinerario() == null ? "SIN_ITINERARIO" : "EN_ALMACEN";
        String tipoAlmacen = codigoAeropuerto.equals(envio.getDestinoIata()) ? "DESTINO_FINAL" : "TRANSITO";
        return new EnvioAlmacenDTO(crearEnvioDTO(envio), codigoAeropuerto, tipoAlmacen, estado);
    }

    private EscalaRutaDTO crearEscalaRuta(VueloInstanciado vuelo) {
        return new EscalaRutaDTO(
                vuelo.getCodigoBase(),
                vuelo.getOrigenIata(),
                vuelo.getDestinoIata(),
                vuelo.getFechaHoraSalidaUtc() != null ? vuelo.getFechaHoraSalidaUtc().toString() : null,
                vuelo.getFechaHoraLlegadaUtc() != null ? vuelo.getFechaHoraLlegadaUtc().toString() : null,
                vuelo.getFechaHoraSalida() != null ? vuelo.getFechaHoraSalida().toString() : null,
                vuelo.getFechaHoraLlegada() != null ? vuelo.getFechaHoraLlegada().toString() : null,
                vuelo.getCapacidadMax(),
                vuelo.isEstaCancelado(),
                vuelo.getMotivoCancelacion()
        );
    }

    private EnvioDTO crearEnvioDTO(Envio envio) {
        return new EnvioDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getFechaHora() != null ? envio.getFechaHora().toString() : null,
                envio.getCantidadMaletas(),
                envio.getIdCliente()
        );
    }

    private long calcularLoteSnapshot(SimulacionState state, String timestampIso) {
        Instant ts = Instant.parse(timestampIso);
        long minutos = Duration.between(
                state.getFechaInicioSimulacion().toInstant(ZoneOffset.UTC), ts
        ).toMinutes();
        if (minutos < 0) return 1;
        return (minutos / state.getKMinutos()) + 1;
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
