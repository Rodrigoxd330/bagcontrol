package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Data
public class SimulacionControl {

    private final String simulacionId;

    private final AtomicBoolean pausada = new AtomicBoolean(false);
    private final AtomicBoolean detenida = new AtomicBoolean(false);
    private final AtomicLong saMs = new AtomicLong(1000);
    private final AtomicLong ultimoEventoEmitido = new AtomicLong(0);

    private String estado = "EN_PROCESO";
    private String algoritmo;
    private int k;
    private String fechaInicio;
    private LocalDateTime fechaCreacion = LocalDateTime.now();

    public SimulacionControl(String simulacionId) {
        this.simulacionId = simulacionId;
    }

    public boolean estaPausada() {
        return pausada.get();
    }

    public boolean estaDetenida() {
        return detenida.get();
    }

    public long getVelocidadMs() {
        return saMs.get();
    }

    public void pausar() {
        pausada.set(true);
        estado = "PAUSADA";
    }

    public void reanudar() {
        pausada.set(false);
        estado = "EN_PROCESO";
    }

    public void detener() {
        detenida.set(true);
        pausada.set(false);
        estado = "DETENIDA";
    }

    public void cambiarVelocidad(long nuevaVelocidadMs) {
        saMs.set(nuevaVelocidadMs);
    }

    public long siguienteEvento() {
        long numero = ultimoEventoEmitido.incrementAndGet();
        return numero;
    }
}