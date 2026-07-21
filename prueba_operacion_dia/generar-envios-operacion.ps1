param(
    [datetime]$FechaHoraLima = (Get-Date),
    [string]$Salida = (Join-Path $PSScriptRoot 'generados')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Husos usados por los datos académicos del proyecto: SPIM=-5, SABE=-3,
# EKCH=+2 y VIDP=+5. La entrada siempre se interpreta como hora de Lima.
$instanteLima = [DateTimeOffset]::new(
    [DateTime]::SpecifyKind($FechaHoraLima, [DateTimeKind]::Unspecified),
    [TimeSpan]::FromHours(-5)
)

$destinos = @(
    @{ Iata = 'SCEL'; Cantidad = 180 },
    @{ Iata = 'SVMI'; Cantidad = 180 },
    @{ Iata = 'SBBR'; Cantidad = 10 },
    @{ Iata = 'SKBO'; Cantidad = 10 },
    @{ Iata = 'SGAS'; Cantidad = 15 },
    @{ Iata = 'SUAA'; Cantidad = 15 },
    @{ Iata = 'EBCI'; Cantidad = 180 },
    @{ Iata = 'LBSF'; Cantidad = 180 },
    @{ Iata = 'OAKB'; Cantidad = 10 },
    @{ Iata = 'OPKC'; Cantidad = 10 },
    @{ Iata = 'EHAM'; Cantidad = 15 },
    @{ Iata = 'OMDB'; Cantidad = 15 }
)

$sedes = @(
    @{ Iata = 'SPIM'; Nombre = 'Lima';         Offset = -5; PrefijoId = 1001 },
    @{ Iata = 'SABE'; Nombre = 'BuenosAires';  Offset = -3; PrefijoId = 1002 },
    @{ Iata = 'EKCH'; Nombre = 'Copenhague';   Offset = 2;  PrefijoId = 1003 },
    @{ Iata = 'VIDP'; Nombre = 'Delhi';        Offset = 5;  PrefijoId = 1004 }
)
$husosPorAeropuerto = @{
    'SCEL' = -3; 'SVMI' = -4; 'SBBR' = -3; 'SKBO' = -5; 'SGAS' = -4; 'SUAA' = -3
    'EBCI' = 2; 'LBSF' = 3; 'OAKB' = 4; 'OPKC' = 5; 'EHAM' = 2; 'OMDB' = 4
}

$marca = $instanteLima.ToString('yyyyMMdd_HHmm')
$directorioSalida = Join-Path $Salida $marca
New-Item -ItemType Directory -Path $directorioSalida -Force | Out-Null
$planesVuelo = [System.Collections.Generic.List[string]]::new()

foreach ($sede in $sedes) {
    $horaLocal = $instanteLima.ToOffset([TimeSpan]::FromHours($sede.Offset))
    $fecha = $horaLocal.ToString('yyyyMMdd')
    $hora = $horaLocal.ToString('HH')
    $minuto = $horaLocal.ToString('mm')
    $lineasArchivo = [System.Collections.Generic.List[string]]::new()
    $lineasManual = [System.Collections.Generic.List[string]]::new()

    for ($indice = 0; $indice -lt $destinos.Count; $indice++) {
        $destino = $destinos[$indice]
        # Los IDs deben ser únicos entre las cuatro sedes porque son claves primarias.
        $idEnvio = '{0:D8}' -f (($sede.PrefijoId * 10000) + $indice + 1)
        $cantidad = '{0:D3}' -f $destino.Cantidad
        $lineasArchivo.Add("$idEnvio-$fecha-$hora-$minuto-$($destino.Iata)-$cantidad-0007729")

        if ($indice -lt 10) {
            $lineasManual.Add(('| {0} | {1} | {2:D3} | 0007729 |' -f ($indice + 1), $destino.Iata, $destino.Cantidad))
        }

        # Los vuelos salen 30 minutos después del inicio y se espacian dos
        # minutos. Asi siguen siendo futuros tras los registros manuales y la
        # carga masiva, que ocurre despues de los periodos de espera de la prueba.
        $salidaUtc = $instanteLima.AddMinutes(30 + (2 * [math]::Floor($indice / 2)))
        $salidaLocal = $salidaUtc.ToOffset([TimeSpan]::FromHours($sede.Offset))
        $esSudamerica = $indice -lt 6
        if ($sede.Iata -in @('SPIM', 'SABE')) {
            $duracionHoras = if ($esSudamerica) { 6 } else { 12 }
        } else {
            $duracionHoras = if ($esSudamerica) { 13 } else { 4 }
        }
        $llegadaUtc = $salidaUtc.AddHours($duracionHoras)
        $llegadaLocal = $llegadaUtc.ToOffset([TimeSpan]::FromHours($husosPorAeropuerto[$destino.Iata]))
        $planesVuelo.Add(('{0}-{1}-{2}-{3}-0150' -f $sede.Iata, $destino.Iata,
                $salidaLocal.ToString('HH:mm'), $llegadaLocal.ToString('HH:mm')))
    }

    $archivoEnvios = Join-Path $directorioSalida ("envios_{0}_{1}_{2}.txt" -f $sede.Iata, $fecha, "$hora$minuto")
    [System.IO.File]::WriteAllLines($archivoEnvios, $lineasArchivo, [System.Text.UTF8Encoding]::new($false))

    $manual = @(
        "# Registros manuales - $($sede.Iata) ($($sede.Nombre))",
        '',
        "Hora local para esta prueba: $($horaLocal.ToString('yyyy-MM-dd HH:mm')) (GMT$($sede.Offset))",
        'El origen, cliente y hora los fija el sistema; ingresa solo destino y cantidad.',
        '',
        '| # | Destino | Maletas | Cliente |',
        '|---:|---|---:|---|'
    ) + $lineasManual
    $archivoManual = Join-Path $directorioSalida ("registros_manuales_{0}.md" -f $sede.Iata)
    [System.IO.File]::WriteAllLines($archivoManual, $manual, [System.Text.UTF8Encoding]::new($false))
}

$archivoVuelos = Join-Path $directorioSalida ("planes_vuelo_adicionales_{0}.txt" -f $marca)
[System.IO.File]::WriteAllLines($archivoVuelos, $planesVuelo, [System.Text.UTF8Encoding]::new($false))

$readme = @(
    '# Datos generados para operación día a día',
    '',
    "Instante base en Lima: $($instanteLima.ToString('yyyy-MM-dd HH:mm zzz'))",
    'Los archivos de cada sede contienen la fecha y hora local equivalente según el huso académico.',
    'Cárgalos desde la cuenta del aeropuerto indicado en el nombre del archivo.',
    'Los identificadores usan prefijos por sede para no colisionar en el sistema.'
)
[System.IO.File]::WriteAllLines((Join-Path $directorioSalida 'README.md'), $readme, [System.Text.UTF8Encoding]::new($false))

$instrucciones = @(
    '# Instrucciones de prueba: operacion dia a dia',
    '',
    'Este generador solo crea archivos; no cambia capacidades, no carga vuelos y no inicia la operacion.',
    '',
    '## Generar los archivos',
    '',
    "Desde la raiz del proyecto ejecuta:",
    '',
    '```powershell',
    ".\prueba_operacion_dia\generar-envios-operacion.ps1 -FechaHoraLima '$($instanteLima.ToString('yyyy-MM-dd HH:mm'))'",
    '```',
    '',
    'Usa la hora exacta de inicio de la prueba en Lima. Los cuatro archivos de envios usan la hora local equivalente de cada sede.',
    'Para una nueva prueba, reemplaza la fecha y hora del comando por la hora actual o programada exacta de Lima; no reutilices una hora de ejemplo.',
    '',
    '## Secuencia de prueba',
    '',
    '1. Antes de la hora de inicio, acuerden una hora exacta de Lima (T0), por ejemplo 14:00, y generen los archivos con esa hora.',
    '2. Entre T0 y T0+5 min: en Operacion dia a dia > Planes de vuelo, carga el archivo de planes generado; luego, en Aeropuertos, cambia SPIM, SABE, EKCH y VIDP a capacidad 999.',
    '3. Entre T0 y T0+5 min: cada registrador inicia sesion con su sede y muestra la fecha/hora local de su computadora. Presenten tambien los archivos generados.',
    '4. Confirmen vuelos, capacidades, cuentas y archivos. Inicien el visualizador de operacion diaria.',
    '5. Esperen 1 minuto. Cada registrador ingresa de 5 a 10 filas de su archivo registros_manuales_<SEDE>.md y anuncia cada registro en voz alta. El origen, cliente y hora los asigna el sistema.',
    '6. Cada grupo recibido se planifica a demanda: espera como maximo 5 segundos para agrupar registros y luego tarda Ta en calcular. La ruta aparece despues de 5 segundos mas Ta.',
    '7. Esperen entre 10 y 15 minutos segun indique el docente. Luego carguen cada envios_<SEDE>_*.txt desde la cuenta de la sede correspondiente.',
    '8. Esperen nuevamente entre 10 y 15 minutos. Seleccionen dos envios planificados y muestren sus rutas en el mapa.',
    '9. Cancelen un vuelo futuro desde la gestion de vuelos y verifiquen la reasignacion de las maletas.',
    '10. Al finalizar, eliminen los vuelos anadidos y restauren las capacidades: SPIM 440, SABE 460, EKCH 480, VIDP 480.',
    '',
    'La preparacion debe durar menos de 5 minutos y el inicio del registro manual debe ocurrir antes de T0+8 min.',
    'Los vuelos generados salen entre T0+30 y T0+40 min para que sean futuros durante la carga masiva y la demostracion.',
    'Los IDs de envios son unicos entre sedes para evitar colisiones.'
)
[System.IO.File]::WriteAllLines((Join-Path $directorioSalida 'INSTRUCCIONES_PRUEBA.md'), $instrucciones, [System.Text.UTF8Encoding]::new($false))

Write-Host "Archivos generados en: $directorioSalida"
foreach ($sede in $sedes) {
    $horaLocal = $instanteLima.ToOffset([TimeSpan]::FromHours($sede.Offset))
    Write-Host ("{0}: {1}" -f $sede.Iata, $horaLocal.ToString('yyyy-MM-dd HH:mm zzz'))
}
