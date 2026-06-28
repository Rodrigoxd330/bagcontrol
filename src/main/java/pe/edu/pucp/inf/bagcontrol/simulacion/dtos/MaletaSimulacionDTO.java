package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaletaSimulacionDTO {
    private String codigoMaleta;   // {idPedido}-M1, -M2, ...
    private String idPedido;       // envío padre (para consultar ruta)
    private String origenIata;
    private String destinoIata;
    private String tipoAlmacen;    // DESTINO_FINAL | TRANSITO
    private String estadoEnvio;
    private int cantidadMaletasEnvio; // total maletas del envío padre
}
