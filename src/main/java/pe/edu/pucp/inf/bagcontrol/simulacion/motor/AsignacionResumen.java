package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsignacionResumen {
    private String idPedido;
    private String estadoAsignacion;
    private String idItinerario;
    private List<String> vuelosUsados;
    private String primerVuelo;
    private String ultimoVuelo;
    private String origenIata;
    private String destinoIata;
    private String horaAsignacion;
    private long bloqueSimulado;
    private boolean contieneVueloCancelado;
}
