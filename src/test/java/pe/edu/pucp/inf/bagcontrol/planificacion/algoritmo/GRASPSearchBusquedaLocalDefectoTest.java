package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regresion del defecto que evaluaba el estado actual sin aplicar el movimiento candidato.
 */
class GRASPSearchBusquedaLocalDefectoTest {

    @Test
    void deberiaAplicarMovimientoCandidatoAntesDeEvaluarlo() throws Exception {
        FitnessEvaluator evaluator = mock(FitnessEvaluator.class);
        when(evaluator.evaluar(any(SolucionRuta.class), any(Map.class))).thenAnswer(invocacion -> {
            SolucionRuta solucion = invocacion.getArgument(0);
            Instant llegada = solucion.getAsignaciones().get(0).getItinerario().getFechaHoraLlegadaUtc();
            double fitness = llegada.equals(Instant.parse("2026-07-20T10:00:00Z")) ? 1.0 : 100.0;
            solucion.setFitness(fitness);
            return fitness;
        });
        GRASPSearch grasp = new GRASPSearch(evaluator);
        Envio envio = envio();
        Itinerario actualLento = new Itinerario(List.of(vuelo(1, "2026-07-20T11:00:00Z", "2026-07-20T12:00:00Z")));
        Itinerario candidatoMejor = new Itinerario(List.of(vuelo(2, "2026-07-20T09:00:00Z", "2026-07-20T10:00:00Z")));
        SolucionRuta inicial = new SolucionRuta();
        inicial.agregarAsignacion(envio, actualLento);
        Aeropuerto lim = aeropuerto("LIM");
        Aeropuerto bog = aeropuerto("BOG");
        Method metodo = GRASPSearch.class.getDeclaredMethod(
                "busquedaLocal", SolucionRuta.class, Map.class, Map.class, int.class);
        metodo.setAccessible(true);

        Object resultado = metodo.invoke(grasp, inicial, Map.of("LIM-BOG", List.of(candidatoMejor)),
                Map.of("LIM", lim, "BOG", bog), 10);
        Method obtenerSolucion = resultado.getClass().getDeclaredMethod("solucion");
        obtenerSolucion.setAccessible(true);
        SolucionRuta mejor = (SolucionRuta) obtenerSolucion.invoke(resultado);

        assertThat(mejor.getAsignaciones().get(0).getItinerario()).isSameAs(candidatoMejor);
    }

    private Envio envio() {
        Envio envio = new Envio();
        envio.setIdPedido("PRUEBA-AGREGADA");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0));
        envio.setCantidadMaletas(1);
        return envio;
    }

    private Aeropuerto aeropuerto(String iata) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setContinente("AMERICA");
        aeropuerto.setCapacidadAlmacen(1_000);
        return aeropuerto;
    }

    private VueloInstanciado vuelo(long codigo, String salidaUtc, String llegadaUtc) {
        Instant salida = Instant.parse(salidaUtc);
        Instant llegada = Instant.parse(llegadaUtc);
        Vuelo base = new Vuelo("LIM", "BOG", LocalTime.of(9, 0), LocalTime.of(10, 0), 100);
        base.setCodigo(codigo);
        return new VueloInstanciado(base,
                LocalDateTime.ofInstant(salida, java.time.ZoneOffset.UTC),
                LocalDateTime.ofInstant(llegada, java.time.ZoneOffset.UTC),
                salida, llegada, 0);
    }
}
