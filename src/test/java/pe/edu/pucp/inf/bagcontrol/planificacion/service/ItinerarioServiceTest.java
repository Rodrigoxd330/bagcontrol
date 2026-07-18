package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItinerarioServiceTest {

    @Test
    void generaUnaEscalaPeroNuncaEncadenaDosEscalas() {
        ItinerarioService service = new ItinerarioService();
        List<VueloInstanciado> vuelos = List.of(
                crearVuelo(1L, "LIM", "BOG", "2026-07-20T09:00:00Z", "2026-07-20T10:00:00Z"),
                crearVuelo(2L, "BOG", "MEX", "2026-07-20T11:00:00Z", "2026-07-20T13:00:00Z"),
                crearVuelo(3L, "MEX", "JFK", "2026-07-20T14:00:00Z", "2026-07-20T17:00:00Z")
        );

        Map<String, List<Itinerario>> itinerarios = service.generarItinerariosPorRuta(vuelos);

        assertThat(itinerarios.get("LIM-MEX"))
                .isNotEmpty()
                .allMatch(itinerario -> itinerario.getCantidadVuelos() == 2);
        assertThat(itinerarios).doesNotContainKey("LIM-JFK");
        assertThat(itinerarios.values().stream().flatMap(List::stream).toList())
                .allMatch(itinerario -> itinerario.getCantidadVuelos() <= 2);
    }

    @Test
    void recorteConservaEscalasAunqueHayaMasRutasDirectas() throws Exception {
        ItinerarioService service = new ItinerarioService();
        List<Itinerario> candidatos = new ArrayList<>();
        Instant base = Instant.parse("2026-07-20T00:00:00Z");
        for (int i = 0; i < 450; i++) {
            Instant salida = base.plusSeconds(i * 60L);
            candidatos.add(new Itinerario(List.of(crearVuelo(
                    (long) i, "LIM", "BOG", salida.toString(), salida.plusSeconds(3600).toString()
            ))));
        }
        for (int i = 0; i < 100; i++) {
            Instant salida = base.plusSeconds(i * 120L);
            candidatos.add(new Itinerario(List.of(
                    crearVuelo(1_000L + i, "LIM", "UIO", salida.toString(), salida.plusSeconds(3600).toString()),
                    crearVuelo(2_000L + i, "UIO", "BOG", salida.plusSeconds(5400).toString(), salida.plusSeconds(9000).toString())
            )));
        }
        Method recortar = ItinerarioService.class.getDeclaredMethod(
                "recortarConDiversidadTipoYTemporal", List.class, int.class
        );
        recortar.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Itinerario> seleccionados = (List<Itinerario>) recortar.invoke(service, candidatos, 500);

        assertThat(seleccionados).hasSize(500);
        assertThat(seleccionados).filteredOn(itinerario -> itinerario.getCantidadVuelos() == 2).hasSize(100);
    }

    private VueloInstanciado crearVuelo(
            Long codigo,
            String origen,
            String destino,
            String salidaUtc,
            String llegadaUtc
    ) {
        Instant salida = Instant.parse(salidaUtc);
        Instant llegada = Instant.parse(llegadaUtc);
        Vuelo vuelo = new Vuelo(
                origen, destino,
                LocalTime.ofInstant(salida, java.time.ZoneOffset.UTC),
                LocalTime.ofInstant(llegada, java.time.ZoneOffset.UTC),
                100
        );
        vuelo.setCodigo(codigo);
        return new VueloInstanciado(
                vuelo,
                LocalDateTime.ofInstant(salida, java.time.ZoneOffset.UTC),
                LocalDateTime.ofInstant(llegada, java.time.ZoneOffset.UTC),
                salida,
                llegada,
                0
        );
    }
}
