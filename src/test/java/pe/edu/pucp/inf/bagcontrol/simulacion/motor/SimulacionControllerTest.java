package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.edu.pucp.inf.bagcontrol.auth.AuthService;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioPorVueloRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EstadoCapacidad;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulacionControllerTest {

    @Test
    void cancelarVueloReemplazaEventoYReprogramaEnviosSinDuplicarlos() {
        WebSocketPublisher publisher = mock(WebSocketPublisher.class);
        SimulacionManager manager = mock(SimulacionManager.class);
        SimulacionController controller = new SimulacionController(publisher, manager, mock(AuthService.class));
        SimulacionState state = new SimulacionState("sim-cancelacion");
        EventoVueloDTO vuelo = vuelo(10L, TipoEvento.VUELO_DESPEGA, List.of("PED-1"));
        state.setUltimoLoteEmitido(new LoteEventosDTO(
                "sim-cancelacion", 1L, "2027-09-10T08:30:00Z", "2027-09-10T09:30:00Z",
                1, new ArrayList<EventoBaseDTO>(List.of(vuelo)), List.of(), 15_000
        ));
        EnvioDTO envio = new EnvioDTO(
                "PED-1", "LIM", "BOG", "2027-09-10T08:35", 2, "CLI-1", false
        );
        state.setEnviosPendientes(new ArrayList<>(List.of(new Envio(
                "PED-1", "LIM", "BOG", java.time.LocalDateTime.parse("2027-09-10T08:35"),
                2, "CLI-1", true, false, false
        ))));
        when(manager.obtenerState("sim-cancelacion")).thenReturn(state);
        when(manager.extraerEnviosPorVuelo("sim-cancelacion", vuelo, "2027-09-10T08:40:00Z"))
                .thenReturn(List.of(envio));

        controller.cancelarVuelo(
                "sim-cancelacion", new EnvioPorVueloRequestDTO(vuelo, "2027-09-10T08:40:00Z")
        );

        EventoVueloDTO cancelado = (EventoVueloDTO) state.getUltimoLoteEmitido().getEventos().get(0);
        assertThat(cancelado.getTipo()).isEqualTo(TipoEvento.VUELO_CANCELADO);
        assertThat(cancelado.getCodigoEnvios()).containsExactly("PED-1");
        assertThat(state.getEnviosPendientes()).extracting("idPedido").containsExactly("PED-1");

        ArgumentCaptor<LoteEventosDTO> lote = ArgumentCaptor.forClass(LoteEventosDTO.class);
        verify(publisher).publicarLote(
                org.mockito.ArgumentMatchers.eq("sim-cancelacion"), lote.capture()
        );
        assertThat(lote.getValue().getEventos()).extracting(EventoBaseDTO::getTipo)
                .containsExactly(TipoEvento.VUELO_CANCELADO);
    }

    private EventoVueloDTO vuelo(Long codigo, TipoEvento tipo, List<String> envios) {
        EventoVueloDTO vuelo = new EventoVueloDTO(
                tipo, "2027-09-10T10:00:00Z", codigo, "LIM", "BOG", EstadoCapacidad.VERDE,
                2, "2027-09-10T05:00", "2027-09-10T07:00",
                "2027-09-10T10:00:00Z", "2027-09-10T12:00:00Z", envios
        );
        vuelo.setCapacidadMax(100);
        return vuelo;
    }
}
