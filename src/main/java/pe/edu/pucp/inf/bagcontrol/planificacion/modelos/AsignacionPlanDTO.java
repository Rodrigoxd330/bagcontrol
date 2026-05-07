package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AsignacionPlanDTO {
    private String idPedido;
    private String origenEnvio;
    private String destinoEnvio;
    private int cantidadMaletas;

    private Long codigoVuelo;
    private String origenVuelo;
    private String destinoVuelo;
    private String horaSalida;
    private String horaLlegada;
    private String horaSalidaUtc;
    private String horaLlegadaUtc;

    private String estado;

    public AsignacionPlanDTO(
            String idPedido,
            String origenEnvio,
            String destinoEnvio,
            int cantidadMaletas,
            Long codigoVuelo,
            String origenVuelo,
            String destinoVuelo,
            String horaSalida,
            String horaLlegada,
            String estado
    ) {
        this(
                idPedido,
                origenEnvio,
                destinoEnvio,
                cantidadMaletas,
                codigoVuelo,
                origenVuelo,
                destinoVuelo,
                horaSalida,
                horaLlegada,
                null,
                null,
                estado
        );
    }

    public AsignacionPlanDTO(
            String idPedido,
            String origenEnvio,
            String destinoEnvio,
            int cantidadMaletas,
            Long codigoVuelo,
            String origenVuelo,
            String destinoVuelo,
            String horaSalida,
            String horaLlegada,
            String horaSalidaUtc,
            String horaLlegadaUtc,
            String estado
    ) {
        this.idPedido = idPedido;
        this.origenEnvio = origenEnvio;
        this.destinoEnvio = destinoEnvio;
        this.cantidadMaletas = cantidadMaletas;
        this.codigoVuelo = codigoVuelo;
        this.origenVuelo = origenVuelo;
        this.destinoVuelo = destinoVuelo;
        this.horaSalida = horaSalida;
        this.horaLlegada = horaLlegada;
        this.horaSalidaUtc = horaSalidaUtc;
        this.horaLlegadaUtc = horaLlegadaUtc;
        this.estado = estado;
    }
}
