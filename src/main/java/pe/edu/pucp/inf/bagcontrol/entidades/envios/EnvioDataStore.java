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
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
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
    private final Map<String, Envio> enviosCrudPorId = new ConcurrentHashMap<>();
    private final Map<String,Boolean> fechasCacheadas = new ConcurrentHashMap<>();
    private final Set<String> enviosEliminados = ConcurrentHashMap.newKeySet();

    private Path spoolPath;
    private int totalEnviosIndexados;
    private final int maxDiasCacheados = 30;
    private final int margenDiasCacheados = 5;

    @Deprecated
    public synchronized void agregarEnvio(Envio envio, Aeropuerto aeropuerto) {
        enviosPorTiempo
                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                .add(envio);
    }

    public synchronized Envio agregarEnvio(NuevoEnvioDTO dto, Aeropuerto aeropuertoOrigen) {
        String idPedido = generarIdPedidoManual(dto.getOrigenIata());

        Envio envio = new Envio();
        envio.setIdPedido(idPedido);
        envio.setOrigenIata(dto.getOrigenIata());
        envio.setDestinoIata(dto.getDestinoIata());
        envio.setCantidadMaletas(dto.getCantidadMaletas());
        envio.setIdCliente(dto.getIdCliente());
        envio.setFechaHora(parsearFechaHoraUtc(dto.getFechaHora()));
        envio.setActivo(true);

        upsert(envio);
        return envio;
    }

    public synchronized void upsert(Envio envio) {
        envio.setActivo(true);
        enviosEliminados.remove(envio.getIdPedido());
        enviosCrudPorId.put(envio.getIdPedido(), envio);
        actualizarContadorManual(envio.getIdPedido(), envio.getOrigenIata());
        System.out.println("[ENVIO-DATASTORE] upsert id=" + envio.getIdPedido());
    }

    public synchronized boolean eliminar(String idPedido) {
        boolean existia = buscarPorId(idPedido).isPresent();
        enviosCrudPorId.remove(idPedido);
        enviosEliminados.add(idPedido);
        System.out.println("[ENVIO-DATASTORE] delete id=" + idPedido);
        return existia;
    }

    public synchronized Optional<Envio> buscarPorId(String idPedido) {
        if (enviosEliminados.contains(idPedido)) {
            return Optional.empty();
        }
        Envio override = enviosCrudPorId.get(idPedido);
        if (override != null) {
            return Optional.of(override);
        }
        return enviosPorTiempo.values().stream()
                .flatMap(List::stream)
                .filter(envio -> idPedido.equals(envio.getIdPedido()))
                .findFirst();
    }

    public synchronized Set<String> obtenerIdsCrudActivos() {
        return Set.copyOf(enviosCrudPorId.keySet());
    }

    public static LocalDateTime parsearFechaHoraUtc(String fechaHora) {
        try {
            return LocalDateTime.ofInstant(Instant.parse(fechaHora), ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException ignored) {
            return LocalDateTime.parse(fechaHora);
        }
    }

    private String generarIdPedidoManual(String origenIata) {
        AtomicInteger contador = contadorManualPorOrigen
                .computeIfAbsent(origenIata, k -> new AtomicInteger(0));
        String candidato;
        do {
            candidato = origenIata + "-M" + String.format("%06d", contador.incrementAndGet());
        } while (buscarPorId(candidato).isPresent());
        return candidato;
    }

    private void actualizarContadorManual(String idPedido, String origenIata) {
        String prefijo = origenIata + "-M";
        if (!idPedido.startsWith(prefijo)) {
            return;
        }
        try {
            int numero = Integer.parseInt(idPedido.substring(prefijo.length()));
            contadorManualPorOrigen
                    .computeIfAbsent(origenIata, k -> new AtomicInteger())
                    .accumulateAndGet(numero, Math::max);
        } catch (NumberFormatException ignored) {
            // Un identificador externo no afecta la secuencia de altas manuales.
        }
    }

    public synchronized void inicializarDesdeZip(Resource enviosZipResource, Map<String, Aeropuerto> mapaAeropuertos)
            throws IOException {
        enviosPorTiempo.clear();
        indicePorDia.clear();
        enviosCrudPorId.clear();
        enviosEliminados.clear();
        contadorManualPorOrigen.clear();
        totalEnviosIndexados = 0;

        Path workDir = Files.createTempDirectory("bagcontrol-envios-idx-");
        workDir.toFile().deleteOnExit();
        Map<LocalDate, Path> fragmentosPorDia = new TreeMap<>();
        Map<LocalDate, BufferedWriter> writersPorDia = new HashMap<>();
        Map<LocalDate, Integer> conteoPorDia = new TreeMap<>();

        try {
            try (ZipInputStream zis = new ZipInputStream(enviosZipResource.getInputStream(), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                int diasCacheados = 0;

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

                        /*if(diasCacheados < maxDiasCacheados) {
                            enviosPorTiempo
                                    .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                                    .add(envio);
                            if(fechasCacheadas.containsKey(diaUtc.toString()))diasCacheados++;
                            fechasCacheadas.put(diaUtc.toString(),true);
                        }*/

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
        Map<String, Envio> resultadoPorId = new java.util.LinkedHashMap<>();
        LocalDate indexDate = inicio.toLocalDate();
        while(indexDate.compareTo(fin.toLocalDate())<=0){
            LocalDate nextDate = indexDate.plusDays(1);
            LocalDateTime _inicio = inicio.isAfter(indexDate.atTime(0,0,0)) ? inicio : indexDate.atTime(0,0,0);
            LocalDateTime _fin = fin.isBefore(nextDate.atTime(0,0,0)) ? fin : nextDate.atTime(0,0,0);
            if (fechasCacheadas.containsKey(indexDate.toString())) {
                System.out.println(_inicio);
                System.out.println(_fin);
                SortedMap<LocalDateTime, List<Envio>> subMapa = new TreeMap<>(enviosPorTiempo.subMap(_inicio, _fin));
                for (List<Envio> lista : subMapa.values()) {
                    for (Envio envio : lista) {
                        if (!enviosEliminados.contains(envio.getIdPedido())
                                && !enviosCrudPorId.containsKey(envio.getIdPedido())) {
                            resultadoPorId.put(envio.getIdPedido(), envio);
                        }
                    }
                }
            } else if (spoolPath != null) {
                System.out.println("spool path");
                //En vez de cargar directamente desde archivo, cargar a cache y acceder desde ahi
                for (Envio envio : obtenerEnviosEnVentanaDesdeSpool(indexDate.atTime(0,0,0), nextDate.plusDays(margenDiasCacheados).atTime(0,0,0))) {
                    //System.out.println(envio.getFechaHora());
                    if (!enviosEliminados.contains(envio.getIdPedido())
                            && !enviosCrudPorId.containsKey(envio.getIdPedido())) {
                        enviosPorTiempo
                                .computeIfAbsent(envio.getFechaHora(), k -> new ArrayList<>())
                                .add(envio);
                    }
                }
                for(int i=0;i<margenDiasCacheados;i++){fechasCacheadas.put(indexDate.plusDays(i).toString(),true);}
                continue;
                //TODO: Metodo para garbage collection de envios que no se acceden desde ningun cliente
                //(revisar que los datos no esten por consultarse o en algun historico
            }
            indexDate = nextDate;
            System.out.println(indexDate.compareTo(fin.toLocalDate()));
        }

        enviosCrudPorId.values().stream()
                .filter(Envio::isActivo)
                .filter(envio -> !envio.getFechaHora().isBefore(inicio) && envio.getFechaHora().isBefore(fin))
                .sorted(Comparator.comparing(Envio::getFechaHora))
                .forEach(envio -> resultadoPorId.put(envio.getIdPedido(), envio));

        return resultadoPorId.values().stream()
                .sorted(Comparator.comparing(Envio::getFechaHora))
                .toList();
    }

    public synchronized int getTotalEnviosCargados() {
        if (enviosCrudPorId.isEmpty() && enviosEliminados.isEmpty()) {
            return !enviosPorTiempo.isEmpty()
                    ? enviosPorTiempo.values().stream().mapToInt(List::size).sum()
                    : totalEnviosIndexados;
        }
        long base = !enviosPorTiempo.isEmpty()
                ? enviosPorTiempo.values().stream().flatMap(List::stream)
                    .map(Envio::getIdPedido).distinct().count()
                : totalEnviosIndexados;
        long eliminadosBase = enviosEliminados.stream()
                .filter(id -> enviosPorTiempo.values().stream()
                        .flatMap(List::stream)
                        .anyMatch(envio -> id.equals(envio.getIdPedido())))
                .count();
        long overridesNuevos = enviosCrudPorId.keySet().stream()
                .filter(id -> enviosPorTiempo.values().stream()
                        .flatMap(List::stream)
                        .noneMatch(envio -> id.equals(envio.getIdPedido())))
                .count();
        return Math.toIntExact(base - eliminadosBase + overridesNuevos);
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
        if (enviosCrudPorId.isEmpty() && enviosEliminados.isEmpty() && spoolPath != null) {
            return obtenerEnviosBasePaginados(
                    inicio, fin, origenIata, destinoIata, idCliente, q,
                    maletasMin, maletasMax, pageable
            );
        }
        if (q != null) {
            Optional<Envio> coincidenciaExacta = buscarPorId(q);
            if (coincidenciaExacta.isPresent()) {
                Envio envio = coincidenciaExacta.get();
                List<Envio> coincidencias = aplicarFiltros(
                        List.of(envio), origenIata, destinoIata, idCliente, q, maletasMin, maletasMax
                ).stream()
                        .filter(e -> !e.getFechaHora().isBefore(inicio) && e.getFechaHora().isBefore(fin))
                        .toList();
                return new PageImpl<>(coincidencias, pageable, coincidencias.size());
            }
        }
        List<Envio> todosFiltrados = aplicarFiltros(
                obtenerEnviosEnVentana(inicio, fin),
                origenIata, destinoIata, idCliente, q, maletasMin, maletasMax
        );
        todosFiltrados = new ArrayList<>(todosFiltrados);
        todosFiltrados.sort(Comparator.comparing(Envio::getFechaHora));
        int total = todosFiltrados.size();
        int desde = (int) pageable.getOffset();
        int hasta = Math.min(desde + pageable.getPageSize(), total);
        List<Envio> contenido = desde >= total ? List.of() : todosFiltrados.subList(desde, hasta);
        return new PageImpl<>(contenido, pageable, total);
    }

    private Page<Envio> obtenerEnviosBasePaginados(
            LocalDateTime inicio, LocalDateTime fin,
            String origenIata, String destinoIata, String idCliente, String q,
            Integer maletasMin, Integer maletasMax,
            Pageable pageable
    ) {
        LocalDate diaInicio = inicio.toLocalDate();
        LocalDate diaFin = fin.toLocalDate();
        NavigableMap<LocalDate, RangoDia> diasEnRango = indicePorDia.subMap(diaInicio, true, diaFin, true);
        boolean tieneFiltros = origenIata != null || destinoIata != null || idCliente != null
                || q != null || maletasMin != null || maletasMax != null;

        if (!tieneFiltros) {
            long totalEstimado = diasEnRango.values().stream()
                    .mapToLong(RangoDia::totalEnvios)
                    .sum();
            long skip = pageable.getOffset();
            List<Envio> contenido = new ArrayList<>();

            try (RandomAccessFile raf = new RandomAccessFile(spoolPath.toFile(), "r")) {
                for (RangoDia rango : diasEnRango.values()) {
                    if (contenido.size() >= pageable.getPageSize()) break;
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
                            if (contenido.size() >= pageable.getPageSize()) break;
                        }
                    }
                    skip = 0;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo leer el indice lazy de envios", e);
            }
            return new PageImpl<>(contenido, pageable, totalEstimado);
        }

        List<Envio> filtrados = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(spoolPath.toFile(), "r")) {
            for (RangoDia rango : diasEnRango.values()) {
                raf.seek(rango.inicio());
                while (raf.getFilePointer() < rango.fin()) {
                    String linea = raf.readLine();
                    if (linea == null) break;
                    if (!cumpleFiltrosLinea(
                            linea, origenIata, destinoIata, idCliente, q, maletasMin, maletasMax
                    )) {
                        continue;
                    }
                    Envio envio = deserializar(linea);
                    if (!envio.getFechaHora().isBefore(inicio) && envio.getFechaHora().isBefore(fin)) {
                        filtrados.add(envio);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el indice lazy de envios", e);
        }
        filtrados.sort(Comparator.comparing(Envio::getFechaHora));
        int total = filtrados.size();
        int desde = (int) pageable.getOffset();
        int hasta = Math.min(desde + pageable.getPageSize(), total);
        return new PageImpl<>(
                desde >= total ? List.of() : filtrados.subList(desde, hasta),
                pageable,
                total
        );
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
