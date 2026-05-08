package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoBaseDTO {
    private TipoEvento tipo;
    private String fechaHoraEvento;
}
