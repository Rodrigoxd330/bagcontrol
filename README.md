1. /domain (El Corazón del Negocio)Cada subdominio dentro de esta carpeta es completamente autónomo y contiene tres capas fundamentales:

- Modelo (Entity.java): La representación de los datos (ej. Aeropuerto.java, Maleta.java).
- DataStore (*DataStore.java): El repositorio en memoria (RAM) optimizado para búsquedas rápidas durante la simulación sin latencia de disco.
- Loader (*Loader.java): El script que se ejecuta al arrancar el servidor (CommandLineRunner), encargado de parsear archivos estáticos (.txt o .zip) y popular el DataStore correspondiente. Los loaders están orquestados mediante la anotación @Order para respetar dependencias (Aeropuertos → Vuelos → Envíos).
2. /domain/envio (SE PUEDE CAMBIAR)(Procesamiento Masivo)Mención especial a este subdominio. Su EnvioDataStore implementa un TreeMap indexado por tiempo para soportar la carga masiva de los 390 MB de datos de envíos provenientes del .zip. Esto permite al algoritmo de búsqueda extraer ventanas de tiempo (sub-lotes) en una fracción de segundo.
3. /engine (El Motor del Simulador)Contiene Simulador.java, el orquestador principal del sistema. Es el encargado de:
- Manejar el Reloj de la Simulación y el factor de aceleración ($K$).
- Hacer avanzar el tiempo de la simulación y despertar al Planificador Metaheurístico.
- Proveer los datos de estado al frontend (Visualizador) en tiempo real.