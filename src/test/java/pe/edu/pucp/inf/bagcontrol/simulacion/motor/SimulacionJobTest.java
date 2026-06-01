package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulacionJobTest {

    @Test
    void publicaColapsoEnDeadlineExactoDentroDelBatch() {
        Aeropuerto origen = crearAeropuerto("LIM", "AMERICA");
        Aeropuerto destino = crearAeropuerto("BOG", "AMERICA");
        Envio envio = crearEnvio();
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(envio, null);

        AeropuertoRepository aeropuertoRepository = mock(AeropuertoRepository.class);
        when(aeropuertoRepository.findAll()).thenReturn(List.of(origen, destino));
        PlanificadorService planificador = mock(PlanificadorService.class);
        when(planificador.calcularSolucion(anyString(), any(), any(), any(), any())).thenReturn(solucion);
        when(planificador.obtenerVuelosCanceladosEnVentana(any(), any())).thenReturn(List.of());
        WebSocketPublisher publisher = mock(WebSocketPublisher.class);
        SimulacionState state = new SimulacionState("sim-test");
        ConfiguracionColapsoDTO configuracion = new ConfiguracionColapsoDTO(0.10, 0.0, 1.0);

        SimulacionJob job = new SimulacionJob(
                "sim-test",
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 22, 8, 15),
                1500,
                "TABU",
                planificador,
                aeropuertoRepository,
                publisher,
                new SimulacionEventosFactory(configuracion),
                state,
                configuracion,
                new SimulacionStateMutator(state, aeropuertoRepository)
        );

        job.run();

        ArgumentCaptor<LoteEventosDTO> captor = ArgumentCaptor.forClass(LoteEventosDTO.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publicarLote(anyString(), captor.capture());
        EventoColapsoDTO evento = captor.getAllValues().stream()
                .flatMap(lote -> lote.getEventos().stream())
                .filter(EventoColapsoDTO.class::isInstance)
                .map(EventoColapsoDTO.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(state.getEstado()).isEqualTo("COLAPSADA");
        assertThat(evento.getFechaHoraEvento()).isEqualTo("2026-07-21T08:15:00Z");
        assertThat(evento.getCausaPrincipal()).isEqualTo("SLA_INCUMPLIDO");
        assertThat(evento.getDetalle().getIdPedido()).isEqualTo("PED-1");
        assertThat(evento.getDetalle().getTipoSla()).isEqualTo("24H_MISMO_CONTINENTE");
        assertThat(evento.getMetricas().getDeadlinePrimerIncumplido()).isEqualTo("2026-07-21T08:15:00Z");
    }

    private Aeropuerto crearAeropuerto(String iata, String continente) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setContinente(continente);
        aeropuerto.setCapacidadAlmacen(100);
        return aeropuerto;
    }

    private Envio crearEnvio() {
        Envio envio = new Envio();
        envio.setIdPedido("PED-1");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 15));
        envio.setCantidadMaletas(3);
        return envio;
    }
}
