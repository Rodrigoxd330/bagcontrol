package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;

import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulacionManagerCatalogoTest {

    @Test
    void refrescaAeropuertosYVuelosCrudAlArrancar() {
        PlanificadorService planificadorService = mock(PlanificadorService.class);
        AeropuertoRepository aeropuertoRepository = mock(AeropuertoRepository.class);
        SimulacionManager manager = new SimulacionManager(
                planificadorService, aeropuertoRepository, mock(WebSocketPublisher.class)
        );
        Aeropuerto spim = aeropuerto("SPIM");
        Aeropuerto spzo = aeropuerto("SPZO");
        Vuelo vuelo = new Vuelo("SPIM", "SPZO", LocalTime.of(9, 30), LocalTime.of(10, 40), 300);
        vuelo.setCodigo(2869L);
        vuelo.setCreadoPorCrud(true);
        when(planificadorService.obtenerAeropuertosSnapshot()).thenReturn(List.of(spim, spzo));
        when(planificadorService.obtenerVuelosBaseSnapshot()).thenReturn(List.of(vuelo));
        when(planificadorService.obtenerIncidenciasSnapshot()).thenReturn(List.of());
        when(aeropuertoRepository.existsById("SPIM")).thenReturn(true);
        when(aeropuertoRepository.existsById("SPZO")).thenReturn(true);
        SimulacionJob job = mock(SimulacionJob.class);

        manager.refrescarCatalogosMaestros(job);

        ArgumentCaptor<SimulacionContextoDatos> captor = ArgumentCaptor.forClass(SimulacionContextoDatos.class);
        verify(job).refrescarContextoDatos(captor.capture());
        assertThat(captor.getValue().aeropuertos())
                .extracting(Aeropuerto::getCodigoIata)
                .containsExactly("SPIM", "SPZO");
        assertThat(captor.getValue().vuelos()).singleElement()
                .satisfies(v -> assertThat(v.getDestinoIata()).isEqualTo("SPZO"));
        VueloInstanciado instancia = new VueloFactory().crearInstanciasDelDia(
                captor.getValue().vuelos(),
                LocalDate.of(2026, 2, 10),
                captor.getValue().aeropuertos()
        ).getFirst();
        assertThat(instancia.getOrigenIata()).isEqualTo("SPIM");
        assertThat(instancia.getDestinoIata()).isEqualTo("SPZO");
    }

    private Aeropuerto aeropuerto(String iata) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setCiudad("Ciudad");
        aeropuerto.setPais("Perú");
        aeropuerto.setContinente("América");
        aeropuerto.setCapacidadAlmacen(1000);
        aeropuerto.setGmt(-5);
        return aeropuerto;
    }
}
