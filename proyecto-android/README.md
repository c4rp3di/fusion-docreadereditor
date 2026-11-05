# DocReaderEditor

App Android nativa y offline (Kotlin 1.9.24, AGP 8.5.0, Gradle 8.7, JDK 17, SDK 34, KSP 1.9.24-1.0.20, Hilt 2.51.1, Room 2.6.1).

## Compilar
1. Abrir la carpeta en Android Studio (Koala o posterior) con JDK 17, o por terminal:
2. Si falta `gradle/wrapper/gradle-wrapper.jar`: `gradle wrapper --gradle-version 8.7`
3. `./gradlew assembleDebug`

## Funcionalidad (v1)
| Formato | Ver | Editar | Notas |
|---|---|---|---|
| TXT, MD, CSV/TSV, JSON, XML | ✅ | ✅ texto plano | MD con vista previa (Markwon) |
| HTML | ✅ texto | ✅ texto plano | al guardar se pierden las etiquetas |
| DOCX | ✅ con formato | ✅ editor tipo Word | ver «Fase 1: editor Word» |
| XLSX | ✅ con fórmulas calculadas | ✅ editor tipo Excel | ver «Fase 2: editor Excel» |
| PDF | ✅ texto seleccionable | ✅ anotar, firmar, formularios, páginas | ver «Fase 3: PDF» |

Biblioteca con búsqueda por nombre y por texto extraído (Room). Acceso a archivos solo con SAF, sin permisos.

## Pendiente respecto al plan
Edición PDF (androidx.pdf Ink, alpha), ajustes, anotaciones, tests.
PDFs con contraseña no soportados (PdfRenderer no admite clave antes de API 35).

## Edición DOCX/XLSX (sin Apache POI)
Se edita el paquete OOXML directamente (ZIP + XML), copiando intactas el resto de entradas.
- DOCX: los párrafos con imágenes, campos, hipervínculos, saltos de página o cambios controlados aparecen bloqueados. Al editar un párrafo se conserva su estilo y el formato de su primer fragmento; el formato intermedio (negritas parciales) se unifica.
- XLSX: al editar se elimina calcChain.xml y se pide recálculo al abrir; las fórmulas dependientes se actualizan al abrir en Excel.
- Guardar escribe sobre el archivo original; si no hay permiso de escritura, ofrece «Guardar como».

## Fase 1: editor Word (DOCX)
Barra de herramientas: deshacer/rehacer, estilo (Normal, Título, Título 1-3), fuente, tamaño, negrita, cursiva,
subrayado, tachado, color de texto, resaltado, alineación (izq/centro/der/justificado), listas con viñetas y
numeradas (sangrar/anular sangría), interlineado, imagen, tablas (insertar, filas/columnas, eliminar),
buscar y reemplazar, quitar formato y borrar párrafo.
- Intro divide el párrafo; Retroceso al inicio lo une con el anterior.
- Solo se reescriben los párrafos/tablas modificados; el resto del archivo queda byte a byte igual.
- Se muestran bloqueados (solo borrables): imágenes y objetos existentes, campos, hipervínculos, saltos de página,
  controles de contenido y cambios controlados. Tablas con celdas combinadas o anidadas: solo se edita el texto.
- Al reescribir un párrafo se pierden sus marcadores internos (marcadores/bookmarks, correctores).
- Sin paginación ni encabezados/pies (vista de flujo continuo).

## Fase 2: editor Excel (XLSX)
- Barra de fórmulas (celda activa + entrada), cabeceras de columna/fila, selección de rango (botón «Rango» o pulsación larga).
- Cálculo de fórmulas: operadores + - * / ^ & % y comparaciones; SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, COUNTBLANK,
  PRODUCT, MEDIAN, IF, IFERROR, AND, OR, NOT, ABS, ROUND/UP/DOWN, INT, MOD, POWER, SQRT, EXP, LN, LOG, LOG10, PI,
  LEN, UPPER, LOWER, TRIM, LEFT, RIGHT, MID, CONCAT(ENATE), REPT, SUBSTITUTE, FIND, SEARCH, VALUE, TEXT, SUMIF,
  COUNTIF, AVERAGEIF, VLOOKUP, INDEX, MATCH, TODAY, NOW, DATE, YEAR, MONTH, DAY, ISBLANK/ISNUMBER/ISTEXT/ISERROR.
  Funciones no soportadas muestran el último valor guardado en el archivo (o #NAME?). Se guardan los resultados en caché.
- Formato: negrita, cursiva, subrayado, color de texto y relleno, tamaño, alineación, bordes, formatos numéricos
  (número, miles, moneda, porcentaje, fecha, hora, texto) y +/− decimales.
- Estructura: insertar/eliminar filas y columnas (las referencias de fórmulas se actualizan), ordenar, copiar/cortar/pegar
  (también desde/hacia el portapapeles del sistema), borrar, Σ autosuma, insertador de funciones, buscar y reemplazar,
  hojas nuevas y renombrar (pulsación larga en la pestaña), deshacer/rehacer (25 pasos).
- Límites: sin filtros ni paneles inmovilizados ni combinar celdas (las combinaciones existentes se conservan); el ancho de
  columna se conserva pero no se edita; el texto enriquecido dentro de una celda se aplana al guardar las hojas modificadas;
  formato condicional, validaciones y gráficos se conservan pero no se desplazan al insertar/eliminar filas o columnas.

## Fase 3: PDF
- **Texto seleccionable y copiable**: pulsación larga sobre una palabra y arrastra los asideros; Copiar, o «Toda la página».
  En PDF escaneados: «Texto ▾ → OCR» (página o documento) crea la capa de texto y ya se puede seleccionar y copiar.
- Anotar: resaltar, subrayar y tachar (desde la selección), dibujo a mano, cuadros de texto, firma (se guarda para reutilizarla),
  sellos, borrar anotación, color y grosor, deshacer/rehacer. Las anotaciones se escriben en el PDF al pulsar Guardar.
- «Reemplazar» tapa el texto seleccionado con blanco y escribe el nuevo encima (no reflujo real).
- Formularios (AcroForm): campos de texto, casillas, opciones y listas.
- Páginas: rotar, eliminar, mover, insertar en blanco, añadir otro PDF, extraer páginas a un PDF nuevo y deshacer.
- Buscar texto en todo el documento; zoom (pellizco o botones); PDF con contraseña (se pide y se abre una copia sin cifrar).
- Exportar a Word (DOCX): texto por párrafos y páginas (usa OCR en páginas escaneadas); resultado aproximado, sin maquetación.
- Límites: las anotaciones quedan como contenido de la página (visibles en cualquier visor, no editables después de guardar);
  no se firma digitalmente (certificado); el reemplazo de texto no conserva la fuente original.

## Vista de páginas (impresión)
- Word: botón «📄 Páginas» de la barra → muestra el documento repartido en páginas (tamaño y márgenes del propio archivo; A4 si no
  los define), con tablas, imágenes, listas, saltos de página y numeración. Zoom con pellizco o botones y «Cerrar» para volver a editar.
- Excel: botón «🖨 Impresión» → cómo se repartiría la hoja al imprimir (papel, orientación, márgenes, escala y «ajustar a una página»
  del archivo), con los saltos entre páginas, formato de celdas y texto desbordado. Orden: primero hacia abajo y luego hacia la derecha.
- Es una aproximación: las fuentes del teléfono no coinciden con las de Word/Excel, por lo que los saltos pueden diferir ligeramente.
- Correcciones: el zoom de PDF y la cuadrícula de Excel usan ahora un contenedor propio (WideScrollView) que sí respeta el ancho.

## Interoperabilidad
- La app aparece en «Abrir con» y «Editar con» del gestor de archivos y de otras apps para: PDF, DOCX, XLSX, TXT, MD, CSV, TSV, JSON,
  HTML, XML y LOG (por tipo MIME y también por extensión, para gestores que envían application/octet-stream).
- «Compartir» desde otras apps (uno o varios archivos) abre el primero; si el origen permite conservar el acceso, se añade a la biblioteca.
- Si el origen solo concede lectura, «Guardar» ofrece «Guardar como».

## Novedades de esta versión
- Zoom fluido por pellizco (con vista previa en vivo) y doble toque en PDF y vista de páginas; botones 🔍 y pellizco en Word y Excel.
- Crear documentos nuevos desde el botón +: Word, Excel, texto, Markdown y CSV.
- CSV/TSV se editan como tabla (detecta ; , o tabulador, conserva BOM y separador). Los formatos de celda no se guardan en CSV.
- «Exportar PDF» desde la vista de páginas de Word y de impresión de Excel.
- Ajustes: tema claro/oscuro/sistema y tamaño del texto del editor.

## Formatos de texto
- **Markdown**: barra con negrita, cursiva, tachado, código (en línea o bloque), títulos (pulsa varias veces para cambiar el nivel),
  listas, numeradas, tareas, citas, enlaces, tablas y línea horizontal; vuelven a quitarse al pulsar de nuevo. «Vista previa» con tablas,
  tachado y tareas (Markwon).
- **JSON**: se abre como árbol (si es válido). Toca un valor para editarlo (texto, número, booleano, nulo, objeto, lista); pulsación larga:
  añadir, renombrar clave, duplicar, mover, eliminar, copiar valor o ruta. «Texto» muestra el JSON; «Formatear» y «Minificar» en modo texto.
  Los números y el orden de las claves se conservan. Se muestran las primeras 1000 entradas de cada nivel en el árbol (el resto se edita en modo Texto).
- **HTML**: se edita el código tal cual (ya no se pierden las etiquetas al guardar), «Vista previa» renderizada (sin JavaScript ni
  conexión) y «Embellecer». 
- Todos: deshacer/rehacer y buscar y reemplazar.

## Fiabilidad
- **Cambios sin guardar**: al volver atrás se pregunta si guardar, descartar o seguir editando.
- **Borrador automático** cada 30 s (y al salir de la app) en Word, Excel, CSV y formatos de texto; al reabrir se ofrece recuperarlo.
- **Copia de seguridad** del original antes de sobrescribirlo (3 por archivo; Ajustes → Ver copias de seguridad).
- **Archivos grandes** (>30 MB): aviso antes de abrir.
- **Pruebas unitarias** (JVM) de referencias, formatos numéricos y fórmulas: `./gradlew testDebugUnitTest`.

## Fidelidad
- **Word**: se leen los estilos reales (tamaños, negrita, color, espaciado, alineación) y se usan en el editor y en la vista de páginas, que
  ahora incluye encabezados y pies con numeración de página.
- **Excel**: SUMIFS, COUNTIFS, AVERAGEIFS, IFS, SWITCH, XLOOKUP, TEXTJOIN, LARGE, SMALL, SIGN, TRUNC; combinar/separar celdas; filtrar filas por
  valores (filas ocultas que se guardan en el archivo).
- **PDF**: miniaturas con reordenar/rotar/eliminar/mover y marcadores del documento.

## Interoperabilidad y experiencia
- Menú «Más ▾»: compartir (el estado actual, con tus cambios), imprimir (PDF, Word, Excel, HTML), exportar a PDF (Word y Excel) e información.
- Biblioteca: favoritos, filtros por tipo, orden, iconos por tipo, importar carpetas, compartir desde la lista.
- Modo noche (🌙) en PDF y vista de páginas. Atajos con teclado físico: Ctrl+S, Z, Y, F y, en Word/Excel, B/I/U.
- Icono adaptable propio.

## Privacidad y publicación
- Bloqueo con huella/PIN, impedir capturas, borrado de temporales, política de privacidad en PRIVACIDAD.md.
- Para publicar en Google Play hay que subir `targetSdk`/`compileSdk` al nivel que exija la tienda (requiere actualizar AGP y Gradle) y probar la
  variante `release` (hay reglas ProGuard para PDFBox, Markwon y Gson).

## Pendiente / límites conocidos
- Excel: sin paneles inmovilizados, sin ancho de columna editable en pantalla (se respeta al imprimir), sin formato condicional ni gráficos.
- Word: sin notas al pie ni comentarios; pies de página y encabezados solo en la vista de páginas.
- PDF: sin firma digital con certificado; las anotaciones quedan fijas tras guardar.
- Interfaz solo en español y sin diseño específico para tablet.

## Excel: nueva vista de hoja (estilo Google Sheets)
- Cuadrícula propia con cabeceras de columna (A, B, C…) y fila (1, 2, 3…) siempre visibles, selección con borde azul, rango arrastrando el asa
  de la esquina o con pulsación larga, y desplazamiento con inercia en las dos direcciones. Zoom por pellizco (fluido) y botones 🔍.
- Anchos de columna y alturas de fila **redimensionables arrastrando el borde de la cabecera** (doble toque en el borde = ajustar al contenido);
  se guardan en el archivo. Columnas/filas ocultas, celdas combinadas (ocupan todo su rango) y texto que desborda a celdas vacías.
- **Paneles inmovilizados** (botón «Inmovilizar»): primera fila/columna o hasta la celda actual; se guardan en el archivo.
- Tocar una cabecera selecciona toda la columna/fila; pulsación larga en una cabecera abre su menú (insertar, eliminar, ocultar, ajustar ancho,
  inmovilizar, ordenar). Doble toque en una celda: editar en la barra de fórmulas. Rellenar hacia abajo/derecha.
- Barra de herramientas reordenada (deshacer, copiar/pegar, formato, números, fórmulas, filas/columnas, orden/filtros).
- Modo oscuro propio para la hoja.
