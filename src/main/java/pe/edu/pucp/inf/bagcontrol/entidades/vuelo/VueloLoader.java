package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(2) // Se ejecuta primero
@RequiredArgsConstructor
public class VueloLoader implements CommandLineRunner{

    private final VueloRepository vueloRepository;

    @Value("${tasf.b2b.data.vuelos}")
    private Resource vuelosRes;

    @Override
    public void run(String... args) throws Exception{
        System.out.println("==================================================");
        System.out.println("✈️ 1. Iniciando carga de Vuelos desde archivo...");
        System.out.println("==================================================");

        List<Vuelo> vuelosNuevos = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(vuelosRes.getInputStream(), StandardCharsets.UTF_8))) {
            //Borrar vuelos aca
            vueloRepository.deleteAll();

            String line;
            while ((line = br.readLine())!=null) {
                System.out.println(line);
                String[] params = line.split("-");
                vuelosNuevos.add(new Vuelo(params[0], params[1],
                        LocalTime.parse(params[2]),
                        LocalTime.parse(params[3]),
                        Integer.parseInt(params[4])
                ));
            }

            //Insertar vuelos aca
            vueloRepository.saveAll(vuelosNuevos);
            System.out.println("Proceso terminado con exito");
        }
        catch(Exception err){
            System.out.println("Error durante la carga de vuelos");
            System.out.println(err.getMessage());
            err.printStackTrace();
        }
    }
}
