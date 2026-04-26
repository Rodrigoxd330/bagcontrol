package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlanificadorUtils {

    public static List<VueloInstanciado> buscarVuelosPosiblesParaEnvio(Envio envio, List<VueloInstanciado> vuelos) {
        List<VueloInstanciado> posibles = new ArrayList<>();

        for (VueloInstanciado vuelo : vuelos) {
            if (vuelo.isEstaCancelado()) continue;

            boolean coincideOrigen = vuelo.getOrigenIata().equalsIgnoreCase(envio.getOrigenIata());
            boolean coincideDestino = vuelo.getDestinoIata().equalsIgnoreCase(envio.getDestinoIata());

            if (coincideOrigen && coincideDestino) {
                posibles.add(vuelo);
            }
        }

        return posibles;
    }

    public static double calcularDuracionVueloHoras(VueloInstanciado vuelo) {
        Duration duracion = Duration.between(vuelo.getFechaHoraSalida(), vuelo.getFechaHoraLlegada());
        return duracion.toMinutes() / 60.0;
    }

    public static boolean excedePlazoMaximo(Envio envio, VueloInstanciado vuelo, List<Aeropuerto> aeropuertos) {
        Map<String, Aeropuerto> mapa = new HashMap<>();

        for (Aeropuerto aeropuerto : aeropuertos) {
            mapa.put(aeropuerto.getCodigoIata(), aeropuerto);
        }

        Aeropuerto origen = mapa.get(envio.getOrigenIata());
        Aeropuerto destino = mapa.get(envio.getDestinoIata());

        if (origen == null || destino == null) {
            return true;
        }

        boolean mismoContinente = origen.getContinente().equalsIgnoreCase(destino.getContinente());

        Duration tiempoTotal = Duration.between(envio.getFechaHora(), vuelo.getFechaHoraLlegada());
        double horasTotales = tiempoTotal.toMinutes() / 60.0;

        if (horasTotales < 0) {
            return true;
        }

        if (mismoContinente) {
            return horasTotales > 24.0;
        } else {
            return horasTotales > 48.0;
        }
    }

    public static List<Movimiento> generarVecindario(SolucionRuta solucion, List<VueloInstanciado> vuelosDisponibles) {
        List<Movimiento> movimientos = new ArrayList<>();

        for (var asignacion : solucion.getAsignaciones()) {
            var envio = asignacion.getEnvio();
            var vueloActual = asignacion.getVuelo();

            List<VueloInstanciado> alternativas = buscarVuelosPosiblesParaEnvio(envio, vuelosDisponibles);

            for (VueloInstanciado vueloNuevo : alternativas) {
                if (vueloActual == null || !esMismoVueloInstanciado(vueloActual, vueloNuevo)) {
                    movimientos.add(new Movimiento(envio, vueloActual, vueloNuevo));
                }
            }
        }

        return movimientos;
    }

    private static boolean esMismoVueloInstanciado(VueloInstanciado a, VueloInstanciado b) {
        if (a == null || b == null) return false;

        return a.getCodigoBase().equals(b.getCodigoBase())
                && a.getFechaHoraSalida().equals(b.getFechaHoraSalida());
    }
}