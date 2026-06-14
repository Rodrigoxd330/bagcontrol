package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioAeropuertoDTO {
    private EnvioDTO envio;
    private Instant fechaHoraSalidaUtc;
    private Instant fechaHoraLlegadaUtc;
}
