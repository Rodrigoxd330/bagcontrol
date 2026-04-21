package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlanificadorUtils {

    public static List<Vuelo> buscarVuelosPosiblesParaEnvio(Envio envio, List<Vuelo> vuelos) {
        List<Vuelo> posibles = new ArrayList<>();

        for (Vuelo vuelo : vuelos) {
            if (vuelo.isEstaCancelado()) continue;

            boolean coincideOrigen = vuelo.getOrigenIata().equalsIgnoreCase(envio.getOrigenIata());
            boolean coincideDestino = vuelo.getDestinoIata().equalsIgnoreCase(envio.getDestinoIata());

            if (coincideOrigen && coincideDestino) {
                posibles.add(vuelo);
            }
        }

        return posibles;
    }

    public static double calcularDuracionVueloHoras(Vuelo vuelo) {
        Duration duracion = Duration.between(vuelo.getHoraSalida(), vuelo.getHoraLlegada());
        long minutos = duracion.toMinutes();

        // si cruza medianoche
        if (minutos < 0) {
            minutos += 24 * 60;
        }

        return minutos / 60.0;
    }

    public static boolean excedePlazoMaximo(Envio envio, Vuelo vuelo, List<Aeropuerto> aeropuertos) {
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

        double duracion = calcularDuracionVueloHoras(vuelo);

        // Regla simple para esta primera iteración:
        // mismo continente: máximo 24 h
        // distinto continente: máximo 48 h
        if (mismoContinente) {
            return duracion > 24.0;
        } else {
            return duracion > 48.0;
        }
    }

    public static List<Movimiento> generarVecindario(SolucionRuta solucion, List<Vuelo> vuelosDisponibles) {
        List<Movimiento> movimientos = new ArrayList<>();

        for (var asignacion : solucion.getAsignaciones()) {
            var envio = asignacion.getEnvio();
            var vueloActual = asignacion.getVuelo();

            List<Vuelo> alternativas = buscarVuelosPosiblesParaEnvio(envio, vuelosDisponibles);

            for (Vuelo vueloNuevo : alternativas) {
                if (vueloActual == null || !vueloNuevo.getCodigo().equals(vueloActual.getCodigo())) {
                    movimientos.add(new Movimiento(envio, vueloActual, vueloNuevo));
                }
            }
        }

        return movimientos;
    }
}