package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectorCancelacionVueloTest {

    @Test
    void exactamenteUnaHoraAntesCancelaLaOcurrenciaDelMismoDia() {
        var vuelo = SelectorCancelacionVuelo.siguienteOcurrencia(
                vuelo(), Instant.parse("2026-06-19T22:25:00Z"), aeropuertos()
        );

        assertThat(vuelo.getFechaHoraSalidaUtc()).isEqualTo(Instant.parse("2026-06-19T23:25:00Z"));
    }

    @Test
    void menosDeUnaHoraAntesCancelaLaOcurrenciaDelDiaSiguiente() {
        var vuelo = SelectorCancelacionVuelo.siguienteOcurrencia(
                vuelo(), Instant.parse("2026-06-19T22:26:00Z"), aeropuertos()
        );

        assertThat(vuelo.getFechaHoraSalidaUtc()).isEqualTo(Instant.parse("2026-06-20T23:25:00Z"));
    }

    private Vuelo vuelo() {
        Vuelo vuelo = new Vuelo("SPIM", "SKBO", LocalTime.of(18, 25), LocalTime.of(20, 25), 340);
        vuelo.setCodigo(24L);
        return vuelo;
    }

    private List<Aeropuerto> aeropuertos() {
        Aeropuerto origen = new Aeropuerto();
        origen.setCodigoIata("SPIM");
        origen.setGmt(-5);
        Aeropuerto destino = new Aeropuerto();
        destino.setCodigoIata("SKBO");
        destino.setGmt(-5);
        return List.of(origen, destino);
    }
}
