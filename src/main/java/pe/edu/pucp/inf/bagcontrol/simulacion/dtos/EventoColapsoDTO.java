package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoColapsoDTO extends EventoBaseDTO {
    private String simulacionId;
    private String fechaSimuladaUtc;
    private int ciclo;
    private String causaPrincipal;
    private List<String> criteriosActivados;
    private MetricasColapsoDTO metricas;

    public EventoColapsoDTO(
            String fechaHoraEvento,
            String simulacionId,
            String fechaSimuladaUtc,
            int ciclo,
            String causaPrincipal,
            List<String> criteriosActivados,
            MetricasColapsoDTO metricas
    ) {
        super(TipoEvento.COLAPSO_DETECTADO, fechaHoraEvento);
        this.simulacionId = simulacionId;
        this.fechaSimuladaUtc = fechaSimuladaUtc;
        this.ciclo = ciclo;
        this.causaPrincipal = causaPrincipal;
        this.criteriosActivados = criteriosActivados;
        this.metricas = metricas;
    }
}
