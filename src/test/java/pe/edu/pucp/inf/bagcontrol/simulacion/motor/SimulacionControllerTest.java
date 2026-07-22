package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.auth.AuthService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloResponseDTO;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulacionControllerTest {

    @Test
    void cancelarVueloDelegaLaSeleccionDeOcurrenciaAlBackend() {
        SimulacionManager manager = mock(SimulacionManager.class);
        SimulacionController controller = new SimulacionController(manager, mock(AuthService.class));
        CancelacionVueloRequestDTO request = new CancelacionVueloRequestDTO();
        request.setInstanteSimulado("2026-06-19T22:25:00Z");
        request.setMotivo("PRUEBA");
        CancelacionVueloResponseDTO esperado = new CancelacionVueloResponseDTO(
                "24|2026-06-19T23:25:00Z", 24L, "SPIM", "SKBO", request.getInstanteSimulado(),
                "2026-06-19T18:25", "2026-06-19T23:25:00Z",
                List.of("PED-1"), 2, false, 1L, "REGISTRADA"
        );
        when(manager.cancelarProximaOcurrencia(
                "sim-cancelacion", 24L, Instant.parse(request.getInstanteSimulado()), "PRUEBA"
        )).thenReturn(esperado);

        CancelacionVueloResponseDTO respuesta = controller.cancelarProximaOcurrencia(
                "sim-cancelacion", 24L, request
        );

        assertThat(respuesta).isEqualTo(esperado);
        verify(manager).cancelarProximaOcurrencia(
                "sim-cancelacion", 24L, Instant.parse(request.getInstanteSimulado()), "PRUEBA"
        );
    }
}
