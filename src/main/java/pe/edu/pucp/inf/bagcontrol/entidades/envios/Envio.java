package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "envios_crud")
public class Envio {
    @Id
    private String idPedido;
    private String origenIata;
    private String destinoIata;
    private LocalDateTime fechaHora;
    private int cantidadMaletas;
    private String idCliente;
    private boolean activo = true;
    private boolean esOperacionDia;
}
