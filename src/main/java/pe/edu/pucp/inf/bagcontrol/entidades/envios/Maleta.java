package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Maleta {

    private String codigo;
    private String origenIata;
    private String destinoIata;
    private String idCliente;
    private LocalDateTime horaRecepcion;
    private EstadoMaleta estado;
    private VueloInstanciado vueloActual;
}