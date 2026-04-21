package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

@Component
public class EnvioDataStore {

    // TreeMap mantiene los envíos ordenados cronológicamente por fecha y hora.
    private final TreeMap<LocalDateTime, List<Envio>> enviosPorTiempo = new TreeMap<>();

    /**
     * Agrega un envío al índice temporal.
     * Se sincroniza para evitar conflictos si hay accesos concurrentes.
     */
    public synchronized void agregarEnvio(Envio envio) {
        enviosPorTiempo
                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                .add(envio);
    }

    /**
     * Extrae todos los envíos dentro de una ventana de tiempo.
     * Se trabaja sobre una copia del submapa para evitar ConcurrentModificationException.
     */
    public synchronized List<Envio> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        SortedMap<LocalDateTime, List<Envio>> subMapa = new TreeMap<>(enviosPorTiempo.subMap(inicio, fin));

        List<Envio> resultado = new ArrayList<>();
        for (List<Envio> lista : subMapa.values()) {
            resultado.addAll(new ArrayList<>(lista));
        }

        return resultado;
    }

    /**
     * Elimina datos previos a un límite dado.
     */
    public synchronized void purgarDatosPasados(LocalDateTime limite) {
        enviosPorTiempo.headMap(limite).clear();
    }

    /**
     * Retorna el total de envíos cargados.
     */
    public synchronized int getTotalEnviosCargados() {
        return enviosPorTiempo.values().stream().mapToInt(List::size).sum();
    }
}