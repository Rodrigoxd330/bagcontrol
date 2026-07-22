package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulacionJobCancelacionOperacionDiaTest {

    @Test
    void cancelaSoloOcurrenciaOperativaLiberaUnaVezYPublicaEvento() throws Exception {
        Instant solicitud = Instant.parse("2026-07-20T07:00:00Z");
        Aeropuerto origen = aeropuerto("SPIM");
        Aeropuerto destino = aeropuerto("SCEL");
        Vuelo plan = new Vuelo("SPIM", "SCEL", LocalTime.of(9, 0), LocalTime.of(12, 0), 100);
        plan.setCodigo(24L);
        VueloInstanciado ocurrencia = new VueloInstanciado(
                plan, LocalDateTime.of(2026, 7, 20, 9, 0), LocalDateTime.of(2026, 7, 20, 12, 0),
                Instant.parse("2026-07-20T09:00:00Z"), Instant.parse("2026-07-20T12:00:00Z"), 0
        );
        Envio envio = new Envio();
        envio.setIdPedido("OP-1");
        envio.setCantidadMaletas(3);
        RutaAsignada asignacion = new RutaAsignada(envio, new Itinerario(List.of(ocurrencia)), false);
        SimulacionState state = new SimulacionState("operacion-activa");
        state.getEnviosEnSeguimiento().put(envio.getIdPedido(), asignacion);
        WebSocketPublisher publisher = mock(WebSocketPublisher.class);
        SimulacionJob job = jobOperativo(state, publisher, plan, origen, destino);

        assertThat(job.listarVuelosCancelables(solicitud)).singleElement().satisfies(cancelable -> {
            assertThat(cancelable.getClaveOcurrencia()).isEqualTo("24|2026-07-20T09:00:00Z");
            assertThat(cancelable.getEnviosAfectados()).containsExactly("OP-1");
            assertThat(cancelable.getCantidadMaletas()).isEqualTo(3);
        });

        var respuesta = job.cancelarOcurrenciaOperacionDia(
                24L, ocurrencia.getFechaHoraSalidaUtc(), solicitud, "CANCELACION_MANUAL"
        );

        assertThat(respuesta.getEstado()).isEqualTo("REGISTRADA");
        assertThat(respuesta.getClaveOcurrencia()).isEqualTo("24|2026-07-20T09:00:00Z");
        assertThat(respuesta.getEnviosAfectados()).containsExactly("OP-1");
        assertThat(respuesta.getCantidadMaletas()).isEqualTo(3);
        assertThat(plan.isEstaCancelado()).isFalse();
        assertThat(ocurrencia.isCanceladoPorIncidencia()).isTrue();
        assertThat(asignacion.getItinerario()).isNull();
        assertThat(state.getEnviosPendientes()).extracting(Envio::getIdPedido).containsExactly("OP-1");
        assertThat(enviosForzados(job)).containsExactly("OP-1");

        ArgumentCaptor<LoteEventosDTO> lote = ArgumentCaptor.forClass(LoteEventosDTO.class);
        verify(publisher).publicarLote(org.mockito.ArgumentMatchers.eq("operacion-activa"), lote.capture());
        assertThat(lote.getValue().getEventos()).singleElement().satisfies(evento -> {
            assertThat(evento.getTipo()).isEqualTo(TipoEvento.VUELO_CANCELADO);
            assertThat(evento.getFechaHoraEvento()).isEqualTo(solicitud.toString());
        });
        assertThat(job.listarVuelosCancelables(solicitud)).isEmpty();
        assertThatThrownBy(() -> job.cancelarOcurrenciaOperacionDia(
                24L, ocurrencia.getFechaHoraSalidaUtc(), solicitud, "CANCELACION_MANUAL"
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelada");
        assertThat(state.getEnviosPendientes()).hasSize(1);
    }

    @Test
    void respetaMargenYRechazaOcurrenciaQueDejoDeSerCancelable() {
        Instant solicitud = Instant.parse("2026-07-20T08:30:00Z");
        Aeropuerto origen = aeropuerto("SPIM");
        Aeropuerto destino = aeropuerto("SCEL");
        Vuelo plan = new Vuelo("SPIM", "SCEL", LocalTime.of(9, 0), LocalTime.of(12, 0), 100);
        plan.setCodigo(24L);
        SimulacionJob job = jobOperativo(new SimulacionState("operacion"), mock(WebSocketPublisher.class), plan, origen, destino);

        assertThatThrownBy(() -> job.cancelarOcurrenciaOperacionDia(
                24L, Instant.parse("2026-07-20T09:00:00Z"), solicitud, "CANCELACION_MANUAL"
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("margen");
    }

    @Test
    void unaSimulacionHistoricaNoAceptaLaEntradaOperativa() {
        Aeropuerto origen = aeropuerto("SPIM");
        Aeropuerto destino = aeropuerto("SCEL");
        Vuelo plan = new Vuelo("SPIM", "SCEL", LocalTime.of(9, 0), LocalTime.of(12, 0), 100);
        plan.setCodigo(24L);
        SimulacionState state = new SimulacionState("historica");
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        SimulacionJob job = new SimulacionJob(
                "historica", LocalDateTime.of(2026, 7, 20, 7, 0), LocalDateTime.of(2026, 7, 21, 7, 0),
                60, "TABU", mock(PlanificadorService.class), repo, mock(WebSocketPublisher.class),
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()), state,
                new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "1",
                new SimulacionContextoDatos(List.of(plan), List.of(origen, destino), List.of(), Instant.now()), null
        );
        assertThatThrownBy(() -> job.cancelarOcurrenciaOperacionDia(
                24L, Instant.parse("2026-07-20T09:00:00Z"), Instant.parse("2026-07-20T07:00:00Z"), "MANUAL"
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("operación día a día");
    }

    @SuppressWarnings("unchecked")
    private Set<String> enviosForzados(SimulacionJob job) throws Exception {
        Field field = SimulacionJob.class.getDeclaredField("enviosForzadosAReplanificar");
        field.setAccessible(true);
        return (Set<String>) field.get(job);
    }

    private SimulacionJob jobOperativo(
            SimulacionState state, WebSocketPublisher publisher, Vuelo plan, Aeropuerto origen, Aeropuerto destino) {
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        when(repo.findAll()).thenReturn(List.of(origen, destino));
        return new SimulacionJob(
                state.getSimulacionId(), LocalDateTime.of(2026, 7, 20, 7, 0), null,
                1, "TABU", mock(PlanificadorService.class), repo, publisher,
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()), state,
                new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "0",
                new SimulacionContextoDatos(List.of(plan), List.of(origen, destino), List.of(), Instant.now()), null
        );
    }

    private Aeropuerto aeropuerto(String iata) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setGmt(0);
        aeropuerto.setCapacidadAlmacen(1000);
        return aeropuerto;
    }
}
