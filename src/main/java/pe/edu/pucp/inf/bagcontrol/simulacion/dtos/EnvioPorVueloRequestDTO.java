package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioPorVueloRequestDTO {
    private EventoVueloDTO flight;
    private String timestamp;
}