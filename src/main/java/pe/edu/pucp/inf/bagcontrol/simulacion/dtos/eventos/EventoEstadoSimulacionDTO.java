package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoEstadoSimulacionDTO extends EventoBaseDTO {
    private String idSimulacion;
    private String horaInicio;
    private String estado;
    private String mensaje;

    public EventoEstadoSimulacionDTO(
            TipoEvento tipo,
            String fechaHoraEvento,
            String idSimulacion,
            String horaInicio,
            String estado,
            String mensaje
    ) {
        super(tipo, fechaHoraEvento);
        this.idSimulacion = idSimulacion;
        this.horaInicio = horaInicio;
        this.estado = estado;
        this.mensaje = mensaje;
    }
}
