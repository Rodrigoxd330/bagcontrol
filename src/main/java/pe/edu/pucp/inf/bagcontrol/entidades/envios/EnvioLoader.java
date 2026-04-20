package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Order(3) // ⚽ Se ejecuta después de Aeropuertos y Vuelos
@RequiredArgsConstructor
public class EnvioLoader implements CommandLineRunner {

    private final EnvioDataStore envioDataStore;

    // Ruta de tu ZIP: classpath:data/envios.zip
    @Value("${tasf.b2b.data.envios}")
    private Resource enviosZipResource;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("==================================================");
        System.out.println("📦 3. Iniciando extracción en memoria del ZIP de Envíos...");

        long inicioTiempo = System.currentTimeMillis();
        int totalEnvios = 0;

        // Abrimos el ZIP directamente en memoria
        try (ZipInputStream zis = new ZipInputStream(enviosZipResource.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;

            // Iteramos por cada archivo .txt dentro del ZIP
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                // Extraemos el origen del nombre del archivo (Asumiendo que el IATA está en el nombre)
                String nombreArchivo = entry.getName();
                String origenIata = extraerIataDelNombre(nombreArchivo);

                // Usamos un BufferedReader sobre el ZipInputStream
                BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                String linea;

                while ((linea = br.readLine()) != null) {
                    linea = linea.trim();
                    if (linea.isEmpty() || linea.startsWith("id_envío")) continue;

                    Envio envio = parsearLinea(linea, origenIata);
                    if (envio != null) {
                        envioDataStore.agregarEnvio(envio);
                        totalEnvios++;
                    }
                }
                zis.closeEntry();
            }
        }

        long finTiempo = System.currentTimeMillis();
        System.out.println("✅ Carga finalizada: " + totalEnvios + " envíos indexados en el TreeMap.");
        System.out.println("⏱️ Tiempo de carga: " + (finTiempo - inicioTiempo) + " ms");
        System.out.println("==================================================\n");

        System.out.println("\n🔍 PRUEBA DE VENTANA DE TIEMPO (K-Simulación):");

        // Definimos la ventana: Del 2 de Enero 2025 a las 00:00 hasta las 04:00 (4 horas)
        LocalDateTime inicioSimulado = LocalDateTime.of(2026, 2, 2, 0, 0);
        LocalDateTime finSimulado = inicioSimulado.plusHours(6);

        long inicioBusqueda = System.nanoTime(); // Usamos nanoTime porque será instantáneo
        List<Envio> rafagaEnvios = envioDataStore.obtenerEnviosEnVentana(inicioSimulado, finSimulado);
        long finBusqueda = System.nanoTime();

        System.out.println("Buscando maletas entre " + inicioSimulado + " y " + finSimulado);
        System.out.println("⚡ Tiempo de extracción: " + (finBusqueda - inicioBusqueda) / 1_000_000.0 + " ms");
        System.out.println("📦 Total de maletas a planificar en este salto: " + rafagaEnvios.size());

        if (!rafagaEnvios.isEmpty()) {
            System.out.println("\nPrimer envío de la ráfaga: " + rafagaEnvios.get(0));
            System.out.println("Último envío de la ráfaga: " + rafagaEnvios.get(rafagaEnvios.size() - 1));
        }
        System.out.println("==================================================\n");
    }

    /**
     * Parsea: 00000001-20250102-01-38-EBCI-006-0007729
     */
    private Envio parsearLinea(String linea, String origenIata) {
        String[] partes = linea.split("-");
        if (partes.length < 7) return null;

        Envio envio = new Envio();
        envio.setIdPedido(partes[0]);
        envio.setOrigenIata(origenIata);
        envio.setDestinoIata(partes[4]);
        envio.setCantidadMaletas(Integer.parseInt(partes[5]));
        envio.setIdCliente(partes[6]);

        // Parseo manual ultra rápido de la fecha y hora
        String fechaStr = partes[1]; // "20250102"
        int anio = Integer.parseInt(fechaStr.substring(0, 4));
        int mes = Integer.parseInt(fechaStr.substring(4, 6));
        int dia = Integer.parseInt(fechaStr.substring(6, 8));
        int hora = Integer.parseInt(partes[2]);
        int minuto = Integer.parseInt(partes[3]);

        envio.setFechaHora(LocalDateTime.of(anio, mes, dia, hora, minuto));

        return envio;
    }

    /**
     * Busca 4 letras mayúsculas seguidas en el nombre del archivo.
     * Ej: "archivos/envios_SKBO.txt" -> retorna "SKBO"
     */
    private String extraerIataDelNombre(String nombreArchivo) {
        // Normalizamos a mayúsculas por si acaso mandan "_envios_skbo_.txt"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_ENVIOS_([A-Z]{4})_").matcher(nombreArchivo.toUpperCase());
        // group(1) devuelve solo lo que está entre los paréntesis del regex
        return m.find() ? m.group(1) : "DESC";
    }
}