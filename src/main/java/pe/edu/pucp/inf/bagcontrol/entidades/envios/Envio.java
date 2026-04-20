package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Envio {
    private String idPedido;
    private String origenIata;
    private String destinoIata;
    private LocalDateTime fechaHora;
    private int cantidadMaletas;
    private String idCliente;
}