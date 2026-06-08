package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.util.*;
import java.util.stream.Collectors;

public class SimulacionStateMutator {

    private final SimulacionState state;
    private final AeropuertoRepository aeropuertoRepository;
    private final Long codigoVueloRegistroStep = 2000L; //se limpia EnviosPorVuelo para que no acapare toda la memoria

    public SimulacionStateMutator(SimulacionState state, AeropuertoRepository aeropuertoRepository) {
        this.state = state;
        this.aeropuertoRepository = aeropuertoRepository;
    }

    public List<Aeropuerto> inicializarAeropuertos() {
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();
        state.getAeropuertosSnapshot().clear();
        state.getInventarioSnapshot().clear();
        aeropuertos.forEach(a -> {
            String iata = a.getCodigoIata();
            state.getAeropuertosSnapshot().put(iata, a);
            state.getInventarioSnapshot().put(iata, 0);
        });

        return aeropuertos;
    }

    public void cargarMaletasInicialesEnOrigenes(SolucionRuta solucion) {
        // Legacy: no se usa en el modo normal WebSocket porque infla el inventario inicial
        // con todos los envios de la ventana. Mantener solo para flujos que pidan precarga.
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;
            String origen = asignacion.getEnvio().getOrigenIata();
            int cantidad = asignacion.getEnvio().getCantidadMaletas();
            sumarMaletas(origen, cantidad);
        }
    }

    public void indexarEnviosPorVuelo(SolucionRuta solucion) {
        Map<Long, List<EnvioDTO>> indice = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            Envio envio = asignacion.getEnvio();
            EnvioDTO envioDTO = new EnvioDTO(
                    envio.getIdPedido(), envio.getOrigenIata(), envio.getDestinoIata(),
                    envio.getFechaHora() != null ? envio.getFechaHora().toString() : null,
                    envio.getCantidadMaletas(), envio.getIdCliente()
            );

            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                indice.computeIfAbsent(vuelo.getCodigoBase(), key -> new ArrayList<>()).add(envioDTO);
            }
        }
        TreeMap<Long, List<EnvioDTO>> newEnvios = new TreeMap<>();
        newEnvios.putAll(state.getEnviosPorVuelo());
        newEnvios.putAll(indice);
        //Limpiar envios cuando llegan al limite
        for(int i=0;i<(newEnvios.size()-codigoVueloRegistroStep);i++){
            newEnvios.remove(newEnvios.firstEntry().getKey());
        }
        state.setEnviosPorVuelo(newEnvios);
    }

    public boolean sumarMaletas(String aeropuertoIata, int cantidad) {
        Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(aeropuertoIata);
        int actual = state.getInventarioSnapshot().getOrDefault(aeropuertoIata, 0);
        if (aeropuerto == null) {
            System.out.println("[SIMULADOR-INVENTARIO] aeropuertoDesconocido=" + aeropuertoIata
                    + " inventarioActual=" + actual
                    + " maletasNoRegistradas=" + cantidad);
            return false;
        }
        state.getInventarioSnapshot().put(aeropuertoIata, actual + cantidad);
        return true;
    }

    public void restarMaletas(String aeropuertoIata, int cantidad) {
        int actual = state.getInventarioSnapshot().getOrDefault(aeropuertoIata, 0);
        state.getInventarioSnapshot().put(aeropuertoIata, Math.max(0, actual - cantidad));
    }

    public boolean descontarMaletasSalidaVuelo(
            String aeropuertoIata,
            int cantidad,
            Long codigoVuelo,
            String horaEvento
    ) {
        int actual = state.getInventarioSnapshot().getOrDefault(aeropuertoIata, 0);
        boolean inconsistencia = cantidad > actual;
        if (inconsistencia) {
            System.out.println("[BUG-CAPACIDAD-ORIGEN] aeropuerto=" + aeropuertoIata
                    + " inventarioActual=" + actual
                    + " maletasVuelo=" + cantidad
                    + " codigoVuelo=" + codigoVuelo
                    + " horaEvento=" + horaEvento);
        }
        state.getInventarioSnapshot().put(aeropuertoIata, Math.max(0, actual - cantidad));
        return inconsistencia;
    }

    public void actualizarInventarioDesdeSolucionColapso(SolucionRuta solucion) {
        Map<String, Integer> cargaPorAeropuerto = new LinkedHashMap<>();
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();
            if (asignacion.getItinerario() == null) {
                cargaPorAeropuerto.merge(asignacion.getEnvio().getOrigenIata(), cantidadMaletas, Integer::sum);
            } else {
                cargaPorAeropuerto.merge(asignacion.getItinerario().getDestinoIata(), cantidadMaletas, Integer::sum);
            }
        }

        for (String codigoIata : state.getAeropuertosSnapshot().keySet()) {
            state.getInventarioSnapshot().put(codigoIata, cargaPorAeropuerto.getOrDefault(codigoIata, 0));
        }
    }

    public int obtenerMaxOcupacionAeropuerto() {
        return state.getInventarioSnapshot().values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public long contarAeropuertosSobreCapacidad() {
        return state.getInventarioSnapshot().entrySet().stream()
                .filter(entry -> {
                    Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(entry.getKey());
                    return aeropuerto != null && entry.getValue() > aeropuerto.getCapacidadAlmacen();
                })
                .count();
    }
}
