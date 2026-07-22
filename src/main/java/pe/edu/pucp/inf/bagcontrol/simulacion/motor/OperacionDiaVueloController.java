package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionOperacionDiaRequestDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloResponseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.VueloCancelableDTO;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/operacion-dia/vuelos")
@RequiredArgsConstructor
public class OperacionDiaVueloController {

    private final SimulacionManager simulacionManager;

    @GetMapping("/cancelables")
    public List<VueloCancelableDTO> listarCancelables() {
        try {
            return simulacionManager.listarVuelosCancelablesOperacionDia(Instant.now());
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping("/cancelaciones")
    public CancelacionVueloResponseDTO cancelar(@RequestBody CancelacionOperacionDiaRequestDTO request) {
        if (request == null || request.getCodigoVuelo() == null || request.getSalidaUtc() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La ocurrencia es obligatoria");
        }
        try {
            return simulacionManager.cancelarOcurrenciaOperacionDia(
                    request.getCodigoVuelo(), Instant.parse(request.getSalidaUtc()), Instant.now()
            );
        } catch (NoSuchElementException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }
}
