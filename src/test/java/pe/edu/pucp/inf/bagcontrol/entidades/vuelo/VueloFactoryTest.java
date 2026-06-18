package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VueloFactoryTest {

    private final VueloFactory vueloFactory = new VueloFactory();

    @Test
    void instanciaUnVueloCrudEnLaFechaSolicitada() {
        Vuelo vuelo = vuelo("SPIM", "SKBO", LocalTime.of(9, 0), LocalTime.of(12, 0));
        vuelo.setCodigo(999L);
        vuelo.setCreadoPorCrud(true);

        VueloInstanciado instancia = vueloFactory.crearInstanciasDelDia(
                List.of(vuelo),
                LocalDate.of(2026, 7, 20),
                List.of(aeropuerto("SPIM", -5), aeropuerto("SKBO", -5))
        ).getFirst();

        assertThat(instancia.getCodigoBase()).isEqualTo(999L);
        assertThat(instancia.getFechaHoraSalidaUtc().toString()).isEqualTo("2026-07-20T14:00:00Z");
        assertThat(instancia.getFechaHoraLlegadaUtc().toString()).isEqualTo("2026-07-20T17:00:00Z");
        assertThat(instancia.isEstaCancelado()).isFalse();
    }

    @Test
    void permiteVueloNocturnoYLlevaLaLlegadaAlDiaSiguiente() {
        Vuelo vuelo = vuelo("SPIM", "SKBO", LocalTime.of(23, 0), LocalTime.of(2, 0));

        VueloInstanciado instancia = vueloFactory.crearInstanciasDelDia(
                List.of(vuelo),
                LocalDate.of(2026, 7, 20),
                List.of(aeropuerto("SPIM", -5), aeropuerto("SKBO", -5))
        ).getFirst();

        assertThat(instancia.getFechaHoraLlegada()).isEqualTo(
                java.time.LocalDateTime.of(2026, 7, 21, 2, 0)
        );
        assertThat(instancia.getFechaHoraLlegadaUtc()).isAfter(instancia.getFechaHoraSalidaUtc());
    }

    private Vuelo vuelo(String origen, String destino, LocalTime salida, LocalTime llegada) {
        Vuelo vuelo = new Vuelo(origen, destino, salida, llegada, 300);
        vuelo.setEstaCancelado(false);
        return vuelo;
    }

    private Aeropuerto aeropuerto(String iata, int gmt) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setGmt(gmt);
        return aeropuerto;
    }
}
