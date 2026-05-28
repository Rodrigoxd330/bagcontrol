package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Order(3) // ⚽ Se ejecuta después de Aeropuertos y Vuelos
@RequiredArgsConstructor
public class EnvioLoader implements CommandLineRunner {

    private final EnvioDataStore envioDataStore;
    private final AeropuertoRepository aeropuertoRepository;

    @Value("${tasf.b2b.data.envios}")
    private Resource enviosZipResource;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("==================================================");
        System.out.println("📦 3. Iniciando extracción en memoria del ZIP de Envíos...");

        // Obtener mapa de aeropuertos para traducir tiempo por gmt
        Map<String, Aeropuerto> mapaAeropuertos = aeropuertoRepository.findAll().stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        long inicioTiempo = System.currentTimeMillis();
        int totalEnvios = 0;

        try (ZipInputStream zis = new ZipInputStream(enviosZipResource.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String nombreArchivo = entry.getName();
                String origenIata = extraerIataDelNombre(nombreArchivo);

                // Buscamos el objeto Aeropuerto de origen para conocer su GMT
                Aeropuerto aeropuertoOrigen = mapaAeropuertos.get(origenIata);
                // Si por alguna razón el aeropuerto no existe, asumimos GMT 0 por seguridad
                int gmtOffset = (aeropuertoOrigen != null) ? aeropuertoOrigen.getGmt() : 0;

                BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                String linea;

                while ((linea = br.readLine()) != null) {
                    linea = linea.trim();
                    if (linea.isEmpty() || linea.startsWith("id_envío")) continue;

                    // Pasamos el gmtOffset al parseador para normalizar la hora
                    Envio envio = parsearLinea(linea, origenIata, gmtOffset);
                    if (envio != null) {
                        envioDataStore.agregarEnvio(envio, aeropuertoOrigen);
                        totalEnvios++;
                    }
                }
                zis.closeEntry();
            }
        }
        long finTiempo = System.currentTimeMillis();
        System.out.println("✅ Carga finalizada: " + totalEnvios + " envíos indexados con hora normalizada a UTC.");
        System.out.println("⏱️ Tiempo de carga: " + (finTiempo - inicioTiempo) + " ms");
        System.out.println("==================================================\n");
    }

    /**
     * Parsea: 00000001-20250102-01-38-EBCI-006-0007729
     * e inyecta el offset local para convertir a LocalDateTime/Instant correctos
     */
    private Envio parsearLinea(String linea, String origenIata, int gmtOffset) {
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

        // 1. Construimos el tiempo tal y como se lee localmente en el archivo
        LocalDateTime horaLocal = LocalDateTime.of(anio, mes, dia, hora, minuto);

        // 2. Le indicamos a Java en qué zona horaria estaba (ej. GMT-5 o GMT+1) y lo convertimos a un Instant UTC
        Instant tiempoUtc = horaLocal.toInstant(ZoneOffset.ofHours(gmtOffset));

        // 3. Almacenamos el LocalDateTime equivalente en UTC para mantener la consistencia con el Job
        envio.setFechaHora(LocalDateTime.ofInstant(tiempoUtc, ZoneOffset.UTC));

        return envio;
    }

    private String extraerIataDelNombre(String nombreArchivo) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_ENVIOS_([A-Z]{4})_").matcher(nombreArchivo.toUpperCase());
        return m.find() ? m.group(1) : "DESC";
    }
}
