package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AeropuertoDTO {
    private String codigoIata;
    private String ciudad;
    private String pais;
    private String continente;
    private int capacidadAlmacen;
    private double latitud;
    private double longitud;
    private int maletasActuales;
}