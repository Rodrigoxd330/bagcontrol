package pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "Aeropuerto")
public class Aeropuerto {

    @Id()
    private String codigoIata;

    private String ciudad;
    private String pais;
    private String continente;
    private int gmt;
    private int capacidadAlmacen;
    private double latitud;
    private double longitud;
}
