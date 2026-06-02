package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class EnvioDataStore {

    private final TreeMap<LocalDateTime, List<Envio>> enviosPorTiempo = new TreeMap<>();
    private final NavigableMap<LocalDate, RangoDia> indicePorDia = new TreeMap<>();

    private Path spoolPath;
    private int totalEnviosIndexados;

    public synchronized void agregarEnvio(Envio envio, Aeropuerto aeropuerto) {
        enviosPorTiempo
                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                .add(envio);
    }

    public synchronized void inicializarDesdeZip(Resource enviosZipResource, Map<String, Aeropuerto> mapaAeropuertos)
            throws IOException {
        enviosPorTiempo.clear();
        indicePorDia.clear();
        totalEnviosIndexados = 0;

        Path workDir = Files.createTempDirectory("bagcontrol-envios-idx-");
        workDir.toFile().deleteOnExit();
        Map<LocalDate, Path> fragmentosPorDia = new TreeMap<>();
        Map<LocalDate, BufferedWriter> writersPorDia = new HashMap<>();
        Map<LocalDate, Integer> conteoPorDia = new TreeMap<>();

        try {
            try (ZipInputStream zis = new ZipInputStream(enviosZipResource.getInputStream(), StandardCharsets.UTF_8)) {
                ZipEntry entry;

                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;

                    String nombreArchivo = entry.getName();
                    String origenIata = extraerIataDelNombre(nombreArchivo);
                    Aeropuerto aeropuertoOrigen = mapaAeropuertos.get(origenIata);
                    int gmtOffset = aeropuertoOrigen != null ? aeropuertoOrigen.getGmt() : 0;

                    BufferedReader br = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    String linea;

                    while ((linea = br.readLine()) != null) {
                        linea = linea.trim();
                        if (linea.isEmpty() || linea.startsWith("id_env")) continue;

                        Envio envio = parsearLineaZip(linea, origenIata, gmtOffset);
                        if (envio == null) continue;

                        LocalDate diaUtc = envio.getFechaHora().toLocalDate();
                        BufferedWriter writer = writersPorDia.computeIfAbsent(diaUtc, dia -> {
                            try {
                                Path fragmento = Files.createTempFile(workDir, "envios-" + dia + "-", ".dat");
                                fragmento.toFile().deleteOnExit();
                                fragmentosPorDia.put(dia, fragmento);
                                return Files.newBufferedWriter(fragmento, StandardCharsets.UTF_8);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                        writer.write(serializar(envio));
                        writer.newLine();
                        conteoPorDia.merge(diaUtc, 1, Integer::sum);
                        totalEnviosIndexados++;
                    }
                    zis.closeEntry();
                }
            } finally {
                for (BufferedWriter writer : writersPorDia.values()) {
                    writer.close();
                }
            }

            spoolPath = Files.createTempFile("bagcontrol-envios-spool-", ".dat");
            spoolPath.toFile().deleteOnExit();
            long posicion = 0L;

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(spoolPath))) {
                for (Map.Entry<LocalDate, Path> entry : fragmentosPorDia.entrySet()) {
                    LocalDate dia = entry.getKey();
                    Path fragmento = entry.getValue();
                    long inicio = posicion;
                    long bytesCopiados = Files.copy(fragmento, out);
                    posicion += bytesCopiados;
                    indicePorDia.put(dia, new RangoDia(inicio, posicion, conteoPorDia.getOrDefault(dia, 0)));
                    Files.deleteIfExists(fragmento);
                }
            }
        } finally {
            Files.deleteIfExists(workDir);
        }
    }

    public synchronized List<Envio> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        if (spoolPath != null) {
            return obtenerEnviosEnVentanaDesdeSpool(inicio, fin);
        }

        SortedMap<LocalDateTime, List<Envio>> subMapa = new TreeMap<>(enviosPorTiempo.subMap(inicio, fin));

        List<Envio> resultado = new ArrayList<>();
        for (List<Envio> lista : subMapa.values()) {
            resultado.addAll(new ArrayList<>(lista));
        }

        return resultado;
    }

    public synchronized int getTotalEnviosCargados() {
        if (spoolPath != null) {
            return totalEnviosIndexados;
        }
        return enviosPorTiempo.values().stream().mapToInt(List::size).sum();
    }

    private List<Envio> obtenerEnviosEnVentanaDesdeSpool(LocalDateTime inicio, LocalDateTime fin) {
        List<Envio> resultado = new ArrayList<>();
        LocalDate diaInicio = inicio.toLocalDate();
        LocalDate diaFin = fin.toLocalDate();

        try (RandomAccessFile raf = new RandomAccessFile(spoolPath.toFile(), "r")) {
            for (RangoDia rango : indicePorDia.subMap(diaInicio, true, diaFin, true).values()) {
                raf.seek(rango.inicio());
                while (raf.getFilePointer() < rango.fin()) {
                    String linea = raf.readLine();
                    if (linea == null) break;
                    Envio envio = deserializar(linea);
                    if (!envio.getFechaHora().isBefore(inicio) && envio.getFechaHora().isBefore(fin)) {
                        resultado.add(envio);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el indice lazy de envios", e);
        }

        return resultado;
    }

    private Envio parsearLineaZip(String linea, String origenIata, int gmtOffset) {
        String[] partes = linea.split("-");
        if (partes.length < 7) return null;

        Envio envio = new Envio();
        envio.setIdPedido(partes[0]);
        envio.setOrigenIata(origenIata);
        envio.setDestinoIata(partes[4]);
        envio.setCantidadMaletas(Integer.parseInt(partes[5]));
        envio.setIdCliente(partes[6]);

        String fechaStr = partes[1];
        int anio = Integer.parseInt(fechaStr.substring(0, 4));
        int mes = Integer.parseInt(fechaStr.substring(4, 6));
        int dia = Integer.parseInt(fechaStr.substring(6, 8));
        int hora = Integer.parseInt(partes[2]);
        int minuto = Integer.parseInt(partes[3]);

        LocalDateTime horaLocal = LocalDateTime.of(anio, mes, dia, hora, minuto);
        Instant tiempoUtc = horaLocal.toInstant(ZoneOffset.ofHours(gmtOffset));
        envio.setFechaHora(LocalDateTime.ofInstant(tiempoUtc, ZoneOffset.UTC));

        return envio;
    }

    private String serializar(Envio envio) {
        return envio.getFechaHora() + "\t"
                + envio.getIdPedido() + "\t"
                + envio.getOrigenIata() + "\t"
                + envio.getDestinoIata() + "\t"
                + envio.getCantidadMaletas() + "\t"
                + envio.getIdCliente();
    }

    private Envio deserializar(String linea) {
        String[] partes = linea.split("\t", -1);
        Envio envio = new Envio();
        envio.setFechaHora(LocalDateTime.parse(partes[0]));
        envio.setIdPedido(partes[1]);
        envio.setOrigenIata(partes[2]);
        envio.setDestinoIata(partes[3]);
        envio.setCantidadMaletas(Integer.parseInt(partes[4]));
        envio.setIdCliente(partes[5]);
        return envio;
    }

    private String extraerIataDelNombre(String nombreArchivo) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("_ENVIOS_([A-Z]{4})_")
                .matcher(nombreArchivo.toUpperCase());
        return m.find() ? m.group(1) : "DESC";
    }

    private record RangoDia(long inicio, long fin, int totalEnvios) {
    }
}
