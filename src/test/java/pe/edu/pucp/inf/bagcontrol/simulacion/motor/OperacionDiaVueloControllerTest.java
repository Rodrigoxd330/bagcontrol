package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionOperacionDiaRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloResponseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.VueloCancelableDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperacionDiaVueloControllerTest {

    @Test
    void listaConHoraRealGeneradaEnBackend() {
        SimulacionManager manager = mock(SimulacionManager.class);
        when(manager.listarVuelosCancelablesOperacionDia(any())).thenReturn(List.of());
        Instant antes = Instant.now();
        new OperacionDiaVueloController(manager).listarCancelables();
        Instant despues = Instant.now();
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(manager).listarVuelosCancelablesOperacionDia(captor.capture());
        assertThat(captor.getValue()).isBetween(antes, despues);
    }

    @Test
    void cancelaOcurrenciaExactaSinInstanteEnviadoPorFrontend() {
        SimulacionManager manager = mock(SimulacionManager.class);
        var request = new CancelacionOperacionDiaRequestDTO();
        request.setCodigoVuelo(24L);
        request.setSalidaUtc("2026-07-22T03:25:00Z");
        CancelacionVueloResponseDTO respuesta = mock(CancelacionVueloResponseDTO.class);
        when(manager.cancelarOcurrenciaOperacionDia(any(), any(), any())).thenReturn(respuesta);

        Instant antes = Instant.now();
        assertThat(new OperacionDiaVueloController(manager).cancelar(request)).isSameAs(respuesta);
        Instant despues = Instant.now();
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(manager).cancelarOcurrenciaOperacionDia(
                org.mockito.ArgumentMatchers.eq(24L),
                org.mockito.ArgumentMatchers.eq(Instant.parse(request.getSalidaUtc())),
                captor.capture()
        );
        assertThat(captor.getValue()).isBetween(antes, despues);
    }

    @Test
    void operacionInactivaEs404YConflictoDeEstadoEs409() {
        SimulacionManager manager = mock(SimulacionManager.class);
        when(manager.listarVuelosCancelablesOperacionDia(any()))
                .thenThrow(new NoSuchElementException("No existe una operación día a día activa"));
        assertThatThrownBy(() -> new OperacionDiaVueloController(manager).listarCancelables())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));

        var request = new CancelacionOperacionDiaRequestDTO();
        request.setCodigoVuelo(24L);
        request.setSalidaUtc("2026-07-22T03:25:00Z");
        when(manager.cancelarOcurrenciaOperacionDia(any(), any(), any()))
                .thenThrow(new IllegalStateException("La ocurrencia ya fue cancelada"));
        assertThatThrownBy(() -> new OperacionDiaVueloController(manager).cancelar(request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }
}
