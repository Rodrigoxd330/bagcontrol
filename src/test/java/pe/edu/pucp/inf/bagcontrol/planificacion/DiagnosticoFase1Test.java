package pe.edu.pucp.inf.bagcontrol.planificacion;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.GRASPSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.ItinerarioService;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticoFase1Test {

    @Test
    void deadlineGlobalAhoraFormaParteDelContratoDeGraspYDeCandidatos() {
        assertThat(GRASPSearch.class.getDeclaredMethods())
                .filteredOn(metodo -> metodo.getName().equals("ejecutarConParametros"))
                .anyMatch(metodo -> java.util.Arrays.stream(metodo.getParameterTypes())
                        .anyMatch(tipo -> tipo == long.class));
        assertThat(ItinerarioService.class.getDeclaredMethods())
                .filteredOn(metodo -> metodo.getName().equals("generarItinerariosPorRuta"))
                .allMatch(metodo -> java.util.Arrays.stream(metodo.getParameterTypes())
                        .noneMatch(tipo -> tipo == long.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recorteActualReservaCuarentaPorCientoParaCandidatosConEscala() throws Exception {
        List<Itinerario> candidatos = new ArrayList<>();
        for (int i = 0; i < 600; i++) candidatos.add(new Itinerario(List.of(vuelo(i, "LIM", "BOG"))));
        for (int i = 0; i < 400; i++) candidatos.add(new Itinerario(List.of(
                vuelo(1_000 + i * 2, "LIM", "UIO"), vuelo(1_001 + i * 2, "UIO", "BOG"))));
        Method recortar = ItinerarioService.class.getDeclaredMethod(
                "recortarConDiversidadTipoYTemporal", List.class, int.class);
        recortar.setAccessible(true);

        List<Itinerario> retenidos = (List<Itinerario>) recortar.invoke(new ItinerarioService(), candidatos, 500);

        long escalas = retenidos.stream().filter(i -> i.getCantidadVuelos() == 2).count();
        assertThat(retenidos).hasSize(500);
        assertThat(escalas).isEqualTo(200);
        assertThat(escalas / (double) retenidos.size()).isEqualTo(0.40);
    }

    @Test
    void candidatosRepresentanRutasCompletasHastaElDestinoFinal() {
        ItinerarioService service = new ItinerarioService();
        Map<String, List<Itinerario>> rutas = service.generarItinerariosPorRuta(List.of(
                vuelo(1, "LIM", "BOG"), vuelo(121, "BOG", "MEX")));

        assertThat(rutas.get("LIM-MEX")).singleElement().satisfies(itinerario -> {
            assertThat(itinerario.getVuelos()).hasSize(2);
            assertThat(itinerario.getVuelos().get(0).getDestinoIata())
                    .isEqualTo(itinerario.getVuelos().get(1).getOrigenIata());
            assertThat(itinerario.getVuelos().get(1).getDestinoIata()).isEqualTo("MEX");
        });
    }

    @Test
    void slaPermaneceEn24HorasMismoContinenteY48Intercontinental() {
        Envio envio = new Envio();
        envio.setIdPedido("AGREGADO");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0));
        Aeropuerto lim = aeropuerto("LIM", "AMERICA");
        Aeropuerto bog = aeropuerto("BOG", "AMERICA");
        Aeropuerto mad = aeropuerto("MAD", "EUROPA");

        assertThat(PlanificadorUtils.calcularDeadlineSla(envio, Map.of("LIM", lim, "BOG", bog)))
                .isEqualTo(Instant.parse("2026-07-21T08:00:00Z"));
        envio.setDestinoIata("MAD");
        assertThat(PlanificadorUtils.calcularDeadlineSla(envio, Map.of("LIM", lim, "MAD", mad)))
                .isEqualTo(Instant.parse("2026-07-22T08:00:00Z"));
    }

    private VueloInstanciado vuelo(long codigo, String origen, String destino) {
        LocalDateTime salida = LocalDateTime.of(2026, 7, 20, 9, 0).plusMinutes(codigo);
        LocalDateTime llegada = salida.plusHours(1);
        Vuelo base = new Vuelo(origen, destino, LocalTime.of(9, 0), LocalTime.of(10, 0), 100);
        base.setCodigo(codigo);
        return new VueloInstanciado(base, salida, llegada,
                salida.toInstant(java.time.ZoneOffset.UTC), llegada.toInstant(java.time.ZoneOffset.UTC), 0);
    }

    private Aeropuerto aeropuerto(String iata, String continente) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setContinente(continente);
        return aeropuerto;
    }
}
