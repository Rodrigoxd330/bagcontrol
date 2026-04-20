package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

@Component
public class EnvioDataStore {
    // ⚽ TreeMap mantiene los envíos ordenados cronológicamente por su fecha de registro.
    // Usamos una lista como valor por si llegan múltiples pedidos en el mismo segundo.
    private final TreeMap<LocalDateTime, List<Envio>> enviosPorTiempo = new TreeMap<>();

    /**
     * Agrega un envío al índice temporal. Usado principalmente por el EnvioLoader.
     */
    public void agregarEnvio(Envio envio) {
        enviosPorTiempo
                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                .add(envio);
    }

    /**
     * Función clave para la simulación:
     * Extrae todos los envíos que se encuentran dentro de una ventana de tiempo específica.
     * @param inicio Tiempo actual de la simulación.
     * @param fin Tiempo actual + (Sa * K).
     */
    public List<Envio> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        // subMap es ultra eficiente (O(log n)) para obtener el rango.
        SortedMap<LocalDateTime, List<Envio>> subMapa = enviosPorTiempo.subMap(inicio, fin);

        List<Envio> resultado = new ArrayList<>();
        subMapa.values().forEach(resultado::addAll);

        return resultado;
    }

    /**
     * Opcional: Elimina datos ya procesados para optimizar el uso de RAM.
     */
    public void purgarDatosPasados(LocalDateTime limite) {
        enviosPorTiempo.headMap(limite).clear();
    }

    public int getTotalEnviosCargados() {
        return enviosPorTiempo.values().stream().mapToInt(List::size).sum();
    }
}
