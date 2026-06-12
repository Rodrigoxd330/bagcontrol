package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@Component
public class EnvioDataStore {

    private final TreeMap<LocalDateTime, List<Envio>> enviosPorTiempo = new TreeMap<>();
    private final NavigableMap<LocalDate, RangoDia> indicePorDia = new TreeMap<>();
    private final Map<String, AtomicInteger> contadorManualPorOrigen = new ConcurrentHashMap<>();

    private Path spoolPath;
    private int totalEnviosIndexados;

    @Deprecated
    public synchronized void agregarEnvio(Envio envio, Aeropuerto aeropuerto) {
        enviosPorTiempo
                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                .add(envio);
    }

    public synchronized Envio agregarEnvio(NuevoEnvioDTO dto, Aeropuerto aeropuertoOrigen) {
        String idPedido = generarIdPedidoManual(dto.getOrigenIata());
        Instant fechaHoraInstant = Instant.parse(dto.getFechaHora());
        LocalDateTime fechaHoraUtc = LocalDateTime.ofInstant(fechaHoraInstant, ZoneOffset.UTC);

        Envio envio = new Envio();
        envio.setIdPedido(idPedido);
        envio.setOrigenIata(dto.getOrigenIata());
        envio.setDestinoIata(dto.getDestinoIata());
        envio.setCantidadMaletas(dto.getCantidadMaletas());
        envio.setIdCliente(dto.getIdCliente());
        envio.setFechaHora(fechaHoraUtc);

        String linea = serializar(envio) + "\n";
        byte[] bytes = linea.getBytes(StandardCharsets.UTF_8);

        try {
            if (spoolPath == null) {
                spoolPath = Files.createTempFile("bagcontrol-envios-spool-", ".dat");
                spoolPath.toFile().deleteOnExit();
            }
            try (RandomAccessFile raf = new RandomAccessFile(spoolPath.toFile(), "rw")) {
                raf.seek(raf.length());
                raf.write(bytes);
            }
            long finPos = Files.size(spoolPath);
            long inicioPos = finPos - bytes.length;
            LocalDate dia = fechaHoraUtc.toLocalDate();
            RangoDia existente = indicePorDia.get(dia);
            if (existente != null) {
                indicePorDia.put(dia, new RangoDia(existente.inicio(), finPos, existente.totalEnvios() + 1));
            } else {
                indicePorDia.put(dia, new RangoDia(inicioPos, finPos, 1));
            }
            totalEnviosIndexados++;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el envio manual al spool", e);
        }

        enviosPorTiempo
                .computeIfAbsent(fechaHoraUtc, k -> new ArrayList<>())
                .add(envio);

        return envio;
    }

    private String generarIdPedidoManual(String origenIata) {
        int num = contadorManualPorOrigen
                .computeIfAbsent(origenIata, k -> new AtomicInteger(0))
                .incrementAndGet();
        return origenIata + "-M" + String.format("%06d", num);
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

                        enviosPorTiempo
                                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                                .add(envio);

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
        if (!enviosPorTiempo.isEmpty()) {
            SortedMap<LocalDateTime, List<Envio>> subMapa = new TreeMap<>(enviosPorTiempo.subMap(inicio, fin));
            List<Envio> resultado = new ArrayList<>();
            for (List<Envio> lista : subMapa.values()) {
                resultado.addAll(new ArrayList<>(lista));
            }
            return resultado;
        }

        if (spoolPath != null) {
            return obtenerEnviosEnVentanaDesdeSpool(inicio, fin);
        }

        return List.of();
    }

    public synchronized int getTotalEnviosCargados() {
        if (!enviosPorTiempo.isEmpty()) {
            return enviosPorTiempo.values().stream().mapToInt(List::size).sum();
        }
        if (spoolPath != null) {
            return totalEnviosIndexados;
        }
        return 0;
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

    public synchronized Page<Envio> obtenerEnviosEnVentanaPaginados(
            LocalDateTime inicio, LocalDateTime fin,
            String origenIata, String destinoIata, String idCliente, String q,
            Integer maletasMin, Integer maletasMax,
            Pageable pageable
    ) {
        if (spoolPath == null) {
            List<Envio> todos = obtenerEnviosEnVentana(inicio, fin);
            List<Envio> filtrados = aplicarFiltros(todos, origenIata, destinoIata, idCliente, q, maletasMin, maletasMax);
            filtrados.sort(Comparator.comparing(Envio::getFechaHora));
            int total = filtrados.size();
            int desde = (int) pageable.getOffset();
            int hasta = Math.min(desde + pageable.getPageSize(), total);
            List<Envio> contenido = desde >= total ? List.of() : filtrados.subList(desde, hasta);
            return new PageImpl<>(contenido, pageable, total);
        }

        LocalDate diaInicio = inicio.toLocalDate();
        LocalDate diaFin = fin.toLocalDate();
        NavigableMap<LocalDate, RangoDia> diasEnRango = indicePorDia.subMap(diaInicio, true, diaFin, true);
        boolean tieneFiltros = origenIata != null || destinoIata != null || idCliente != null
                || q != null || maletasMin != null || maletasMax != null;

        if (!tieneFiltros) {
            long totalEstimado = 0;
            for (RangoDia r : diasEnRango.values()) {
                totalEstimado += r.totalEnvios();
            }
            long skip = pageable.getOffset();
            int pageSize = pageable.getPageSize();
            List<Envio> contenido = new ArrayList<>();

            try (RandomAccessFile raf = new RandomAccessFile(spoolPath.toFile(), "r")) {
                for (RangoDia rango : diasEnRango.values()) {
                    if (contenido.size() >= pageSize) break;
                    if (skip >= rango.totalEnvios()) {
                        skip -= rango.totalEnvios();
                        continue;
                    }
                    raf.seek(rango.inicio());
                    long leidosEnDia = 0;
                    while (raf.getFilePointer() < rango.fin()) {
                        String linea = raf.readLine();
                        if (linea == null) break;
                        leidosEnDia++;
                        if (leidosEnDia <= skip) continue;
                        Envio envio = deserializar(linea);
                        if (!envio.getFechaHora().isBefore(inicio) && envio.getFechaHora().isBefore(fin)) {
                            contenido.add(envio);
                            if (contenido.size() >= pageSize) break;
                        }
                    }
                    skip = 0;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo leer el indice lazy de envios", e);
            }

            return new PageImpl<>(contenido, pageable, totalEstimado);
        }

        List<Envio> todosFiltrados = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(spoolPath.toFile(), "r")) {
            for (RangoDia rango : diasEnRango.values()) {
                raf.seek(rango.inicio());
                while (raf.getFilePointer() < rango.fin()) {
                    String linea = raf.readLine();
                    if (linea == null) break;
                    if (!cumpleFiltrosLinea(linea, origenIata, destinoIata, idCliente, q, maletasMin, maletasMax)) continue;
                    Envio envio = deserializar(linea);
                    if (!envio.getFechaHora().isBefore(inicio) && envio.getFechaHora().isBefore(fin)) {
                        todosFiltrados.add(envio);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el indice lazy de envios", e);
        }

        todosFiltrados.sort(Comparator.comparing(Envio::getFechaHora));
        int total = todosFiltrados.size();
        int desde = (int) pageable.getOffset();
        int hasta = Math.min(desde + pageable.getPageSize(), total);
        List<Envio> contenido = desde >= total ? List.of() : todosFiltrados.subList(desde, hasta);
        return new PageImpl<>(contenido, pageable, total);
    }

    private boolean cumpleFiltrosLinea(String linea, String origenIata, String destinoIata,
                                       String idCliente, String q, Integer maletasMin, Integer maletasMax) {
        String[] partes = linea.split("\t", -1);
        if (partes.length < 6) return false;
        if (origenIata != null && !partes[2].equalsIgnoreCase(origenIata)) return false;
        if (destinoIata != null && !partes[3].equalsIgnoreCase(destinoIata)) return false;
        if (idCliente != null && !partes[5].equals(idCliente)) return false;
        if (q != null && !partes[1].contains(q)) return false;
        if (maletasMin != null || maletasMax != null) {
            int maletas = Integer.parseInt(partes[4]);
            if (maletasMin != null && maletas < maletasMin) return false;
            if (maletasMax != null && maletas > maletasMax) return false;
        }
        return true;
    }

    private List<Envio> aplicarFiltros(
            List<Envio> envios,
            String origenIata, String destinoIata, String idCliente, String q,
            Integer maletasMin, Integer maletasMax
    ) {
        return envios.stream()
                .filter(e -> origenIata == null || e.getOrigenIata().equalsIgnoreCase(origenIata))
                .filter(e -> destinoIata == null || e.getDestinoIata().equalsIgnoreCase(destinoIata))
                .filter(e -> idCliente == null || e.getIdCliente().equals(idCliente))
                .filter(e -> q == null || e.getIdPedido().contains(q))
                .filter(e -> maletasMin == null || e.getCantidadMaletas() >= maletasMin)
                .filter(e -> maletasMax == null || e.getCantidadMaletas() <= maletasMax)
                .toList();
    }

    private Envio parsearLineaZip(String linea, String origenIata, int gmtOffset) {
        String[] partes = linea.split("-");
        if (partes.length < 7) return null;

        Envio envio = new Envio();
        envio.setIdPedido(origenIata + "-" + partes[0]);
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
