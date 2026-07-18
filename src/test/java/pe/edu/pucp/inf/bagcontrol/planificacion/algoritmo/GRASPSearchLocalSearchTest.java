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
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GRASPSearchLocalSearchTest {

    @Test
    void seleccionaMejoraSinMutarOriginalNiPerderOtrasAsignaciones() throws Exception {
        Envio e1 = envio("E1", "LIM", "BOG");
        Envio e2 = envio("E2", "LIM", "BOG");
        Itinerario lenta = itinerario(vuelo(1, "LIM", "BOG", 11, 12, 100));
        Itinerario rapida = itinerario(vuelo(2, "LIM", "BOG", 9, 10, 100));
        SolucionRuta original = new SolucionRuta();
        original.agregarAsignacion(e1, lenta);
        original.agregarAsignacion(e2, lenta);

        SolucionRuta resultado = buscar(original, Map.of("LIM-BOG", List.of(rapida)));

        assertThat(resultado.getAsignaciones()).hasSize(2);
        assertThat(resultado.getAsignaciones()).allMatch(a -> a.getItinerario() == rapida);
        assertThat(original.getAsignaciones()).allMatch(a -> a.getItinerario() == lenta);
        assertThat(resultado.getAsignaciones()).isNotSameAs(original.getAsignaciones());
        assertThat(resultado.getAsignaciones()).extracting(a -> a.getEnvio().getIdPedido())
                .containsExactlyInAnyOrder("E1", "E2");
    }

    @Test
    void rechazaMovimientoQueEmpeoraFitness() throws Exception {
        Envio envio = envio("E1", "LIM", "BOG");
        Itinerario rapida = itinerario(vuelo(1, "LIM", "BOG", 9, 10, 100));
        Itinerario lenta = itinerario(vuelo(2, "LIM", "BOG", 11, 12, 100));
        SolucionRuta original = new SolucionRuta(); original.agregarAsignacion(envio, rapida);
        assertThat(buscar(original, Map.of("LIM-BOG", List.of(lenta))).getAsignaciones().get(0).getItinerario())
                .isSameAs(rapida);
    }

    @Test
    void rechazaMovimientoQueSobrecargaUnTramo() throws Exception {
        Envio e1 = envio("E1", "LIM", "BOG"); e1.setCantidadMaletas(2);
        Envio e2 = envio("E2", "LIM", "BOG"); e2.setCantidadMaletas(2);
        Itinerario actual = itinerario(vuelo(1, "LIM", "BOG", 11, 12, 10));
        Itinerario sinCapacidad = itinerario(vuelo(2, "LIM", "BOG", 9, 10, 3));
        SolucionRuta original = new SolucionRuta(); original.agregarAsignacion(e1, actual); original.agregarAsignacion(e2, actual);
        SolucionRuta resultado = buscar(original, Map.of("LIM-BOG", List.of(sinCapacidad)));
        assertThat(resultado.getAsignaciones()).filteredOn(a -> a.getItinerario() == sinCapacidad).hasSize(1);
        assertThat(esValida(resultado)).isTrue();
    }

    @Test
    void validaRutaDirectaYEscalaCompletas() throws Exception {
        SolucionRuta directa = solucion(envio("E1", "LIM", "BOG"),
                itinerario(vuelo(1, "LIM", "BOG", 9, 10, 100)));
        SolucionRuta escala = solucion(envio("E2", "LIM", "BOG"), itinerario(
                vuelo(2, "LIM", "UIO", 9, 10, 100), vuelo(3, "UIO", "BOG", 11, 12, 100)));
        assertThat(esValida(directa)).isTrue();
        assertThat(esValida(escala)).isTrue();
    }

    @Test
    void rechazaRutaParcialDiscontinuaOConConexionFueraDeRango() throws Exception {
        assertThat(esValida(solucion(envio("E1", "LIM", "BOG"),
                itinerario(vuelo(1, "LIM", "UIO", 9, 10, 100))))).isFalse();
        assertThat(esValida(solucion(envio("E2", "LIM", "BOG"), itinerario(
                vuelo(2, "LIM", "UIO", 9, 10, 100), vuelo(3, "MIA", "BOG", 11, 12, 100))))).isFalse();
        assertThat(esValida(solucion(envio("E3", "LIM", "BOG"), itinerario(
                vueloMinutos(4, "LIM", "UIO", 9, 0, 10, 0),
                vueloMinutos(5, "UIO", "BOG", 10, 20, 11, 20))))).isFalse();
        assertThat(esValida(solucion(envio("E4", "LIM", "BOG"), itinerario(
                vuelo(6, "LIM", "UIO", 9, 10, 100), vuelo(7, "UIO", "BOG", 23, 23, 100))))).isFalse();
    }

    @Test
    void mantieneSla24Y48Horas() throws Exception {
        Envio regional = envio("E1", "LIM", "BOG");
        Envio intercontinental = envio("E2", "LIM", "MAD");
        assertThat(esValida(solucion(regional, itinerario(vueloDia(1, "LIM", "BOG", 1, 9, 10))))).isFalse();
        assertThat(esValida(solucion(intercontinental, itinerario(vueloDia(2, "LIM", "MAD", 1, 9, 10))))).isTrue();
        assertThat(esValida(solucion(intercontinental, itinerario(vueloDia(3, "LIM", "MAD", 2, 9, 10))))).isFalse();
    }

    @Test
    void noDuplicaEnviosNiCapacidadAlEvaluarCandidatos() throws Exception {
        Envio envio = envio("E1", "LIM", "BOG");
        Itinerario actual = itinerario(vuelo(1, "LIM", "BOG", 11, 12, 1));
        Itinerario mejor = itinerario(vuelo(2, "LIM", "BOG", 9, 10, 1));
        SolucionRuta original = solucion(envio, actual);
        SolucionRuta resultado = buscar(original, Map.of("LIM-BOG", List.of(mejor)));
        assertThat(resultado.getAsignaciones()).hasSize(1);
        assertThat(resultado.getAsignaciones().get(0).getEnvio()).isSameAs(envio);
        assertThat(esValida(resultado)).isTrue();
    }

    private SolucionRuta buscar(SolucionRuta original, Map<String, List<Itinerario>> candidatos) throws Exception {
        FitnessEvaluator evaluator = mock(FitnessEvaluator.class);
        when(evaluator.evaluar(any(SolucionRuta.class), any(Map.class))).thenAnswer(inv -> {
            SolucionRuta s = inv.getArgument(0);
            double f = s.getAsignaciones().stream().map(a -> a.getItinerario().getFechaHoraLlegadaUtc())
                    .mapToLong(Instant::toEpochMilli).sum();
            s.setFitness(f); return f;
        });
        GRASPSearch grasp = new GRASPSearch(evaluator);
        Method m = GRASPSearch.class.getDeclaredMethod("busquedaLocal", SolucionRuta.class, Map.class, Map.class, int.class);
        m.setAccessible(true);
        Object r = m.invoke(grasp, original, candidatos, aeropuertos(), 50);
        Method solucion = r.getClass().getDeclaredMethod("solucion"); solucion.setAccessible(true);
        return (SolucionRuta) solucion.invoke(r);
    }

    private boolean esValida(SolucionRuta solucion) throws Exception {
        GRASPSearch grasp = new GRASPSearch(mock(FitnessEvaluator.class));
        Method m = GRASPSearch.class.getDeclaredMethod("solucionValida", SolucionRuta.class, Map.class);
        m.setAccessible(true); return (boolean) m.invoke(grasp, solucion, aeropuertos());
    }

    private Map<String, Aeropuerto> aeropuertos() {
        Map<String, Aeropuerto> mapa = new HashMap<>();
        for (String iata : List.of("LIM", "BOG", "UIO", "MIA", "MAD")) {
            Aeropuerto a = new Aeropuerto(); a.setCodigoIata(iata); a.setCapacidadAlmacen(10_000);
            a.setContinente(iata.equals("MAD") ? "EUROPA" : "AMERICA"); mapa.put(iata, a);
        }
        return mapa;
    }

    private Envio envio(String id, String origen, String destino) {
        Envio e = new Envio(); e.setIdPedido(id); e.setOrigenIata(origen); e.setDestinoIata(destino);
        e.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0)); e.setCantidadMaletas(1); return e;
    }
    private SolucionRuta solucion(Envio e, Itinerario i) { SolucionRuta s = new SolucionRuta(); s.agregarAsignacion(e, i); return s; }
    private Itinerario itinerario(VueloInstanciado... vuelos) { return new Itinerario(List.of(vuelos)); }
    private VueloInstanciado vuelo(long id, String o, String d, int s, int l, int capacidad) {
        return vueloEnFecha(id, o, d, 0, s, 0, l, capacidad);
    }
    private VueloInstanciado vueloDia(long id, String o, String d, int diaOffset, int s, int l) {
        return vueloEnFecha(id, o, d, diaOffset, s, diaOffset, l, 100);
    }
    private VueloInstanciado vueloMinutos(long id, String o, String d, int sh, int sm, int lh, int lm) {
        Vuelo base = new Vuelo(o, d, LocalTime.of(sh, sm), LocalTime.of(lh, lm), 100); base.setCodigo(id);
        LocalDate fecha = LocalDate.of(2026, 7, 20);
        return new VueloInstanciado(base, fecha.atTime(sh, sm), fecha.atTime(lh, lm),
                fecha.atTime(sh, sm).toInstant(ZoneOffset.UTC), fecha.atTime(lh, lm).toInstant(ZoneOffset.UTC), 0);
    }
    private VueloInstanciado vueloEnFecha(long id, String o, String d, int ds, int sh, int dl, int lh, int capacidad) {
        Vuelo base = new Vuelo(o, d, LocalTime.of(sh, 0), LocalTime.of(lh, 0), capacidad); base.setCodigo(id);
        LocalDate baseDate = LocalDate.of(2026, 7, 20);
        LocalDateTime salida = baseDate.plusDays(ds).atTime(sh, 0), llegada = baseDate.plusDays(dl).atTime(lh, 0);
        return new VueloInstanciado(base, salida, llegada, salida.toInstant(ZoneOffset.UTC), llegada.toInstant(ZoneOffset.UTC), 0);
    }
}
