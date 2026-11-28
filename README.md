# AloNotes

App Android personal de apuntes (estilo Penly Notes) con el aspecto de Alouncher.
Kotlin, `com.alonotes.app`, minSdk 26, targetSdk 34. Sin librerías extra (solo recyclerview y core).

## Fase 1 — Interfaz
- Biblioteca "Mis notas": carpetas y notas en cuadrícula, migas de pan, menú flotante (+):
  Nuevo documento, Importar documento (próximamente), Crear carpeta. Renombrar y eliminar funcionan.
- Editor: barra de herramientas completa (todo "Próximamente" salvo volver y crear páginas),
  diálogo "Crear páginas nuevas" (A5/A4/A3/A2, orientación, color, cantidad) y contador de páginas.
- Ajustes: color de acento (12 colores) y opciones "Próximamente".
- Ícono: A cursiva dibujada de un solo trazo que termina en un broche.

Datos: `SharedPreferences "notes"` (clave `items`, JSON) y `"settings"` (clave `accent`).
No cambiar `applicationId`.

## Fase 1b — Kotlin y limpieza
- Proyecto migrado de Java a Kotlin (Kotlin 1.9.24, AGP 8.5.2). Mismos formatos de datos y comportamiento.
- Eliminado el botón flotante de lápiz del editor (el lápiz del borde es el panel edge de la tablet).
- versionCode 2 / versionName 0.1.1.

## Fase 2 — Personalización estilo AloTube (v0.2.0)
- Quitados los íconos de correo y ayuda de la biblioteca.
- **Carpetas como AloTube:** toque largo + arrastre reordena (carpetas con carpetas, notas con notas;
  `local_order`); toque largo sin mover abre el menú de la carpeta: Establecer icono (imagen del
  dispositivo, `folder_icons`), Quitar fondo y Archivar/Desarchivar (`folder_archive`). Con ícono propio
  la tarjeta muestra solo la imagen.
- **Ajustes (tuerca):** Apariencia › Fondo / Estilos de letra / Color de acento; Archivados.
  - Fondos por pantalla (Mis notas, Editor, Ajustes): imagen o **video** (mudo, en bucle), ajuste
    Rellenar/Estirar/Ajustar, capa oscura al 60 % (`backgrounds`, `filesDir/backgrounds/`).
  - Estilos de letra: `assets/fonts/` (pon ahí tus .ttf/.otf), importadas (`filesDir/fonts/`) y 5 del sistema.
  - Acento: 12 colores + colores propios por **HEX** (máx. 12, `accent_custom`). El acento tiñe los íconos
    de barras y hojas.
- Cambiar acento o fuente recrea las pantallas abiertas.

**No cambia:** `applicationId`, claves `notes`/`items` y `settings`/`accent`, formato de las notas,
el ícono del launcher (color fijo).

## Fase 3 — Opciones del lápiz (v0.2.1)
- Al tocar el lápiz en el editor se resalta y aparecen sus opciones a la derecha de las herramientas:
  tipo (lápiz / pluma / pincel, cambia también el ícono), suavizado (menú con valor y deslizador),
  3 grosores y 4 colores. Toque largo en un color: selector con círculo cromático, barra de valor,
  HEX (misma validación que el acento) y paleta de hasta 14 colores guardados.
- Datos: SharedPreferences `pen`. Aún NO hay trazo sobre la hoja: eso es la siguiente fase.

**No cambia:** datos de notas, carpetas, fondos, fuentes y acento.

## Fase 4 — Vistas del editor y menú lateral (v0.3.0)
- **Ojo** en la barra del editor: alterna entre *una hoja* (centrada, ajustada a la pantalla según su
  orientación; deslizar con un dedo cambia de hoja: las hojas van de izquierda a derecha: a la izquierda = siguiente, a la derecha = anterior) y
  *continua* (todas las hojas en columna con un pequeño espacio; solo visual, sin herramientas).
- Zoom con pellizco en ambas vistas (visual, no cambia la hoja): una hoja mínimo 25 % del ajuste;
  continua nunca más pequeña que su tamaño base. Dos dedos rotan la hoja en la vista de una hoja
  (no se guarda). Doble toque restablece. El stylus se ignora (reservado para dibujar).
- **Capas:** ícono con número independiente (`LayersView.count`), por ahora 1; solo en vista de una hoja.
- **Menú lateral** en la biblioteca (tres rayas o deslizar desde la izquierda): Todas las notas,
  Sin categorizar, Favoritos, Papelera, Categorías, Mis notas, Nueva categoría (todo "Próximamente").

**No cambia:** datos de notas, carpetas, fondos, fuentes, acento y opciones del lápiz.

### v0.3.1 — correcciones
- Orden de hojas de izquierda a derecha (deslizar a la izquierda avanza a la siguiente).
- Vista continua: ya se desplaza (la columna tenía la altura de la pantalla); hoja 1 arriba.
- Al reabrir una nota las hojas aparecían vacías hasta pulsar el ojo: la vista se reconstruía en
  mitad del layout y la hoja quedaba sin medir; ahora se reconstruye justo después.

## Fase 5 — Dibujar y borrar (v0.4.0)
- **Lápiz / pluma / pincel** dibujan en la hoja actual (vista de una hoja) con el color, grosor
  (fino/normal/denso) y suavizado elegidos. Pluma y pincel varían el grosor con la presión del stylus
  (con el dedo, según la velocidad). Dos dedos siguen haciendo zoom/rotación/desplazamiento; si se usa
  stylus, la palma (dedos sueltos) se ignora.
- **Borrador** quita partes de los trazos (parte los trazos) con tamaño fino/normal/grande
  (radios 1,5 / 4 / 9 mm); muestra un círculo donde borra.
- Los trazos se guardan por nota en `filesDir/strokes/<id>.bin` (milímetros de la hoja, binario compacto),
  en segundo plano; se borran junto con la nota. La vista continua los muestra (solo lectura).
- Las flechas junto al contador ahora pasan a la hoja anterior / siguiente.
- Pendiente: deshacer/rehacer, miniaturas con trazos en la biblioteca, resaltador.

### v0.4.1 — marca texto
- Nuevo ícono de marca texto y herramienta funcional: trazo de lápiz parcialmente transparente (alpha ≈ 45 %,
  se ve lo de debajo) con grosor fino/mediano/grande (2,5 / 5 / 9 mm) y sus propios 4 colores
  (toque largo = selector con círculo cromático, HEX y paleta guardada compartida con el lápiz).
- El borrador también quita partes del marca texto. Datos: `pen` (`hl_*`); en el archivo de trazos es el tipo 3.

## Fase 6 — Panel flotante de capas (v0.5.0)
- Al tocar el ícono de capas (vista de una hoja) aparece un **panel flotante** (`LayersPanel.kt`) sobre la hoja,
  abajo a la derecha. Se **arrastra por su barra superior** y nunca sale del área de hojas.
- Dos estados: **expandido** (barra "Capas" con arrastre, minimizar y cerrar; lista de capas con ojo; barra inferior con
  porcentaje y control deslizante) y **minimizado** (cuadro con el número de capas; se arrastra igual y un toque lo expande).
  Cerrar (X) devuelve el ícono de capas. En vista continua el panel se oculta y vuelve al regresar a una hoja.
- Estado (abierto, minimizado, posición) se conserva al girar la tablet o cambiar acento/fuente; no se guarda al cerrar la nota.
- Solo visual: la lista muestra "Capa 1" y la opacidad aún no cambia nada. Las capas reales (crear, ocultar, reordenar, opacidad)
  y su formato de guardado son la siguiente fase. Íconos nuevos: `MINIMIZE` y `GRIP`.

**No cambia:** datos de notas, trazos (`strokes/*.bin`), carpetas, fondos, fuentes, acento y opciones del lápiz.

## Fase 6b — Herramientas del panel de capas (v0.5.1)
- El panel ahora es una fila de tres partes: **barra izquierda** (40 dp: agregar, duplicar, bajar capa), **columna central**
  (lista, modo de fusión y opacidad) y **barra derecha** (40 dp: configuración, bloquear alfa, subir, eliminar, más opciones).
- La fila "Capa 1" lleva miniatura con fondo de tablero (transparencia), ojo, nombre y menú (≡) a la derecha.
- Sobre la opacidad hay una fila con flecha-a-línea, candado pequeño y el modo "Normal".
- **Todos los botones nuevos muestran "Próximamente"** (`UiKit.soon`); no hay lógica real de capas todavía.
- Íconos nuevos en `Icons.kt`: `COPY`, `ARROW_DOWN`, `ARROW_UP`, `ARROW_DOWN_LINE`, `CHECKER` y `DOTS_V`. Se usan colores existentes.

## Ajustes de diseño del panel de capas (v0.5.2)
- Duplicar lleva un pequeño **+** en la esquina superior derecha (ícono `COPY_PLUS`).
- Barra derecha: se quitaron candado y flecha arriba; ahora es tablero, **mover/transformar** (`MOVE`), basura y más opciones.
- Fila sobre la opacidad: solo flecha-a-línea y candado a la izquierda; sin texto "Normal" ni flecha desplegable.

## Fase 7 — Capas reales, primera parte (v0.6.0)
- Cada hoja tiene ahora **capas** (`Layer`: id, name, isVisible, opacity y canvas = sus trazos; `PageLayers` en `Strokes.kt`).
  Siempre hay al menos una. Las capas viven en memoria mientras la nota está abierta.
- **+** agrega una capa vacía justo encima de la seleccionada y la selecciona (máx. 30). **Basura** elimina la seleccionada
  (pide confirmación si tiene trazos; la última capa no se puede eliminar). Tocar una fila la selecciona.
- Se dibuja y se borra solo en la capa seleccionada. El render respeta `isVisible` y `opacity` (aún sin controles).
- El número del ícono de capas y del panel minimizado muestra cuántas capas tiene la hoja.
- **Guardado:** el formato `strokes/*.bin` **no cambió**: al guardar se juntan los trazos de todas las capas (de abajo hacia arriba).
  Al volver a abrir la nota, cada hoja carga en una sola capa. Guardar capas por separado es la siguiente parte.

## Fase 7b — Ojo de capa (v0.6.1)
- El ojo de cada capa **alterna `isVisible`**: visible = ojo en acento; oculta = ojo tachado (`EYE_OFF`) y nombre en gris.
  El dibujo de la hoja salta las capas ocultas. No se puede dibujar ni borrar en una capa oculta (avisa con un aviso).
- La visibilidad **aún no se guarda**: al reabrir la nota todo vuelve a verse (los trazos ocultos siguen guardados).

## Fase 8 — Guardado de la estructura de capas (v0.7.0)
- `LayerRepository.kt` guarda `filesDir/layers/<idNota>.json` con kotlinx.serialization: por hoja, cada capa (nombre, orden,
  visibilidad, opacidad), cuál está seleccionada y **cuántos trazos tiene**. Se guarda al agregar, eliminar, mostrar/ocultar,
  seleccionar y al dibujar o borrar; se carga en `onCreate` justo después de los trazos.
- `strokes/<id>.bin` **no cambió**: sigue guardando todos los trazos juntos (abajo → arriba). Con el conteo por capa, al abrir cada
  trazo vuelve a su capa. Si los datos no cuadran (archivo dañado o a medias), todo cae en la primera capa y se conserva la estructura.
- `Layer` ahora es `@Serializable` (su `canvas` es `@Transient`). Gradle: plugin `org.jetbrains.kotlin.plugin.serialization`
  1.9.24 y `kotlinx-serialization-json:1.6.3`. Al eliminar una nota también se borra su archivo de capas.

## Fase 9 — Reordenar capas (v0.8.0)
- La lista de capas del panel ahora es un **RecyclerView** (antes un ScrollView) con `ItemTouchHelper` (UP/DOWN), igual que la
  biblioteca. El arrastre **solo empieza al tocar el ≡** de la fila (`startDrag` manual; sin pulsación larga en la fila).
- La capa que queda arriba en la lista es la que se dibuja encima (el modelo va de abajo hacia arriba y la lista se muestra al revés).
- Al soltar se actualiza el orden, se redibuja la hoja y se guarda (trazos + capas), así el orden sobrevive al cerrar la app.
- `PageLayers.move()` conserva la capa seleccionada. Sin dependencias nuevas (recyclerview ya estaba).

## Ícono nuevo (v0.8.1)
- Ícono del launcher: la "A" dorada estilizada sobre negro (diseño `Icono_AloNotes.png`). `res/drawable/ic_fg.xml` es ahora un **Vector Drawable**
  (trazado de la A, relleno #E1B656, `evenOdd` para el hueco) escalado para caber en la zona segura de 66 dp; nítido en cualquier densidad.
- El fondo es `@color/ic_bg`, ahora #000000 (antes #141414). `ic_launcher.xml` e `ic_launcher_round.xml` no cambiaron (mismos nombres).
- La app usa el color fijo del ícono; no depende del acento dinámico.

## Fase 10 — Categorías, favoritos y modos de vista (v0.9.0)
- **Datos:** `Category(id, nombre, esPorDefecto)` y `CategoryStore` (`Categories.kt`, clave `cats` en las preferencias `notes`).
  "Mis notas" (id `default`) se crea sola. `NoteStore.Item` ganó `categoryId` (clave `c`) e `isFavorite` (clave `fav`).
  Al abrir por primera vez (`NoteStore.migrate`) todo lo existente queda en `default`; lo que no tenga categoría cuenta como `default`.
- **Menú lateral:** se quitó "Sin categorizar". Las categorías son una lista dinámica; tocar una carga sus carpetas y notas y el título
  de la barra pasa a ser su nombre. Toque largo: Renombrar / Eliminar (la de por defecto no se elimina; al eliminar una, su contenido
  pasa a "Mis notas"). "Nueva categoría" crea y abre una.
- **Modos de vista:** `MODO_CARPETAS` (por defecto), `MODO_TODAS_LAS_NOTAS` (lista plana de la categoría actual) y `MODO_FAVORITOS`
  (lista plana de favoritas de todas las categorías). En las listas planas no se reordena.
- **Favoritos:** en las opciones de una nota (toque largo sin mover, o la flecha de la tarjeta) aparece "Añadir a Favoritos" /
  "Quitar de Favoritos" (estrella vacía / rellena, `STAR_FILL`).
- **Botón +:** en carpetas abre el menú de siempre; en listas planas crea una nota directamente (en Favoritos nace favorita).
- El orden manual de la raíz se guarda por categoría (la de por defecto conserva la clave de antes). Capas y trazos no cambian.

## Reordenar categorías (v0.9.1)
- El menú lateral ahora es **un solo RecyclerView** (`DrawerPanel` en `SideDrawer.kt`): encabezado, categorías y pie "Nueva categoría".
- Las categorías se reordenan con `ItemTouchHelper.SimpleCallback` (UP/DOWN): **toque largo + arrastrar** (como la biblioteca,
  `startDrag` manual desde el toque largo). Si se suelta sin mover, se abre Renombrar/Eliminar. (v0.9.2: antes se intentó empezar
  el arrastre moviendo el ícono de carpeta, pero no era fiable ni evidente.)
- "Mis notas" (id `default`) siempre es la primera: no se arrastra ni se puede soltar sobre ella (`onMove`/`canDropOver`), aunque se renombre.
- Al soltar se guarda el orden (`CategoryStore.reorder`, que además fuerza la por defecto primero). La fila arrastrada se muestra levantada (sombra).
- No cambia: modos de vista, favoritos, capas ni trazos.

## Fase 11 — Imágenes y herramienta Seleccionar (v0.10.0)
- **Imágenes** (barra superior): abre la galería (`ACTION_OPEN_DOCUMENT`, como Ajustes). La imagen se copia reducida (máx. 2048 px, EXIF) a
  `filesDir/images/<idNota>/` y se coloca al centro de la hoja (60 % de la hoja), **dentro de la capa seleccionada** (`Layer.items`).
  Al insertarla se activa la herramienta **Seleccionar** (tool 4), que también está en su botón de la barra.
- **Modelo modular:** `SelectableItem` (`bounds` sin rotar en mm, `rotation`, `scale`, `draw`, `hit`) en `Selectables.kt`; `ImageItem` lo implementa.
  Textos y trazos podrán implementarlo después. `Xf` reúne la geometría (rotar, local↔hoja, ángulo con `atan2`, redimensionar).
- **Recuadro:** contorno, 8 tiradores (esquinas y bordes) y círculo de giro arriba, dibujados en pantalla (`PageViewport.dispatchDraw`),
  así se ven aunque la imagen salga de la hoja. Tocar el centro arrastra; un tirador redimensiona (esquinas con proporción fija: proyección
  del puntero sobre la diagonal; bordes: solo ancho o alto; el punto opuesto no se mueve); el círculo gira (`atan2` del puntero respecto al centro).
- **Recorte:** al dibujar la hoja (`PageView`), las imágenes se recortan con `clipRect` a los límites de la hoja; van debajo de los trazos de su capa
  y respetan visibilidad y opacidad de la capa.
- **Guardado:** `layers/<idNota>.json` guarda por capa sus imágenes (archivo, rectángulo, rotación). Se borran con la nota.
- El menú de 3 puntos muestra "Próximamente". (v0.10.1: ya no está en la barra superior; es un botón flotante que
  aparece solo con un elemento seleccionado, a la izquierda del recuadro, y lo acompaña al mover, girar, redimensionar,
  hacer zoom o desplazar la hoja.)

## Fase 12 — Gestor de páginas (v0.11.0)
- El ícono de cuadrícula del editor abre `PageManagerActivity` (nueva, registrada en el manifiesto): cuadrícula (`GridLayoutManager`)
  con la miniatura de cada hoja y "Page N". Atrás a la izquierda y **EDIT** a la derecha.
- **Edición:** Atrás sale del modo; Deshacer/Rehacer, Seleccionar todo, Copiar y Basura muestran "Próximamente". Tocar una hoja la marca
  (borde de acento, capa translúcida y una marca) o la desmarca. Fuera de la edición, tocar una hoja vuelve al editor en esa hoja.
- **Arrastrar y soltar:** toque largo + arrastrar (`ItemTouchHelper`) reordena. Se guarda: `NoteStore.movePage` (hojas) y se reordenan los
  trazos y las capas. El editor se recarga al volver (`recreate`). Si se suelta sin mover, aparece el menú (Copiar/Borrar hoja o seleccionadas,
  Pegar adelante/atrás) que por ahora solo muestra avisos.
- Las miniaturas se dibujan en segundo plano (fondo, imágenes y trazos de las capas visibles). `StrokeStore.flush()` y `LayerRepository.flush()`
  esperan a las escrituras pendientes antes de leer.

## Fase 13 — Copiar, pegar y borrar hojas (v0.12.0)
- **Copiar** (barra o menú) guarda en `PageClipboard` una copia profunda de las hojas marcadas (datos de página, capas, trazos duplicados
  e imágenes copiadas a `filesDir/clipboard/`). No borra nada. **Pegar adelante / atrás** inserta hojas NUEVAS justo después de la última / antes
  de la primera hoja del grupo, con trazos e imágenes propios (archivos con nombre nuevo). **Borrar** (barra o menú) pide confirmación y no deja
  eliminar todas: "La nota debe tener al menos una hoja". Después se borran los archivos de imagen que ya nadie usa.
- **Sin cruces entre hojas:** `PageManagerActivity.commit()` reconstruye hojas (`NoteStore.setPages`), trazos y capas desde UNA misma lista ordenada
  (`pages`); cada hoja lleva consigo su JSON y su `PageLayers`. No se guardan números de posición: solo el orden de la lista. El arrastre también usa `commit()`.
- Al volver al editor se espera a las escrituras pendientes (`flush`) y el editor se recarga.

## Menú flotante de la imagen (v0.12.1)
- El botón de 3 puntos junto al recuadro abre un `PopupWindow` vertical (fondo `sf2` redondeado) al lado del botón, con 7 botones en orden:
  Enlace, Recortar, Recorte libre (tijeras), Voltear horizontal, Voltear vertical (el mismo ícono girado 90°), Opacidad y Bloquear objeto.
  Cada uno cierra el menú y muestra "<Nombre>: Próximamente". Íconos nuevos en `Icons.kt`: `LINK`, `CROP`, `CUT`, `FLIP`, `OPACITY`.
- Se cierra al tocar fuera (el toque además llega a la hoja), al tocar otra vez el botón de 3 puntos, y junto con el botón y el recuadro al deseleccionar.

## Voltear y opacidad de la imagen (v0.13.0)
- `SelectableItem` ahora tiene `isFlippedH`, `isFlippedV` y `opacity`. `ImageItem` los dibuja en `drawBitmap`: primero gira, luego refleja sobre
  su centro (en sus propios ejes) y aplica el alpha de la Paint. Se guardan en `layers/<idNota>.json` (`ImageDto`: `fh`, `fv`, `op`; los datos viejos
  usan los valores por defecto) y viajan con copiar/pegar hojas (`PageClipboard`) y las miniaturas del gestor.
- Menú de 3 puntos: **Voltear horizontal/vertical** alterna el espejo; **Opacidad** abre un panel flotante con porcentaje y SeekBar (0–100) que
  cambia la imagen en tiempo real y guarda al soltar o cerrar. El resto sigue en "Próximamente". Un solo popup abierto a la vez.

## Recorte de imagen (v0.14.0)
- Menú de 3 puntos → **Recortar** entra al modo recorte: se ocultan el botón de 3 puntos y el círculo de giro, la imagen se oscurece al 50 % fuera de la
  ventana y aparecen X (cancelar) y Check (confirmar) arriba. Los 8 tiradores redimensionan la VENTANA (siempre dentro de la imagen); arrastrar el centro
  desliza la imagen por debajo de la ventana (sin dejar la ventana fuera de la imagen).
- **Confirmar** pasa la ventana al sistema local de la imagen (`Xf.toLocal` con su giro), la convierte en fracciones, refleja si está volteada, corta con
  `Bitmap.createBitmap` y guarda un archivo NUEVO (`ImageFiles.write`); el recuadro se recalcula desde los píxeles recortados. El archivo viejo se borra
  cuando ya se escribió el JSON de capas que apunta al nuevo. **Cancelar** devuelve la imagen a su sitio. Al salir vuelven el botón y el círculo.
- `ImageItem.file` y `baseW` ahora son `var`. El JSON de capas ya guardaba el nombre del archivo y el rectángulo, así que no cambió su formato.

## Recorte libre (v0.15.0)
- Menú de 3 puntos → **Recorte libre** (tijeras): se oculta el botón de 3 puntos, la hoja se oscurece un poco, sale la instrucción "Dibuja el contorno a recortar"
  y arriba aparecen X y Check (los mismos del recorte rectangular). Se dibuja un trazo continuo con el dedo (línea dorada punteada en tiempo real); al levantar
  el dedo se cierra solo y lo de fuera del lazo se oscurece más. Se puede volver a dibujar hasta confirmar. **X** borra el trazo y sale sin tocar la imagen.
- **Check:** lleva el lazo al sistema de la imagen (deshace giro, centrado y espejo), `Path.computeBounds` da el rectángulo mínimo, se crea un `Bitmap` de ese tamaño y
  se dibuja la imagen con `clipPath(lazo)` (PNG con transparencia fuera del lazo). El recuadro se recalcula desde ese rectángulo.
- Guardado igual que el recorte rectangular (`commitCrop`: archivo nuevo, el viejo se borra cuando el JSON ya apunta al nuevo).

## Deshacer y rehacer (v0.16.0)
- `PageHistory` (`History.kt`): dos pilas de `PageSnap` (estado lógico de la hoja actual ANTES de cada acción: capas, trazos —referencias, no copias—,
  imágenes con sus propiedades y la capa seleccionada; sin bitmaps: solo el archivo). Máximo 30 pasos. Una acción nueva borra la pila de rehacer.
  Se vacía al cambiar de hoja y, como vive en el editor, al salir de la nota.
- Se registra al completar: un trazo, un borrado (una entrada por gesto), insertar imagen, mover/redimensionar/girar, voltear, opacidad (una por vez que se
  abre el panel), recorte rectangular y libre, y las acciones de capas (agregar, eliminar, reordenar, mostrar/ocultar) para que la hoja nunca quede incoherente.
- Botones: las flechas curvas de arriba a la derecha (`ROT_L` deshacer, `ROT_R` rehacer); en gris si no hay nada o en vista continua.
- Al recortar, el archivo anterior ya no se borra (el historial lo necesita). Los archivos de imagen sin uso (de más de un minuto) se limpian al abrir la nota.

## Enlaces en imágenes (v0.17.0)
- `ImageItem` tiene `linkType` (`LINK_NONE`, `LINK_WEB`, `LINK_INTERNAL`) y `linkData` (URL, o `idNota|númeroDeHoja`). Se guardan en `layers/<idNota>.json`
  (`ImageDto.lt`/`ld`), viajan con copiar/pegar hojas y con el historial de deshacer.
- Menú de 3 puntos → **Enlace**: Enlace Web (pide la URL; solo http/https), Enlace Interno (lista con las hojas de esta nota y las otras notas; de una nota con
  varias hojas se elige la hoja) y Quitar enlace. Los diálogos están en `EditorActivity`.
- **Abrir:** con lápiz, marcador o sin herramienta, un toque corto y quieto (< 450 ms) sobre una imagen con enlace (capa visible) lo abre en vez de dibujar:
  el trazo se descarta. Web → `ACTION_VIEW`; interno → otra hoja de la misma nota (`goToPage`) o `EditorActivity` de la otra nota con "id" y "page".
  Una imagen SIN enlace no intercepta nada: el lápiz dibuja encima como siempre. Con la herramienta Seleccionar las imágenes se seleccionan como antes.
- Al mantener presionada una imagen con enlace aparece un ícono de cadena en su esquina superior derecha.

## Bloquear objeto (v0.18.0)
- `SelectableItem.isLocked` (`ImageItem.isLocked`), guardado en el JSON de capas (`ImageDto.lk`), en el historial (`ItemSnap.lk`) y al copiar/pegar hojas.
- Menú de 3 puntos → **Bloquear objeto**: la imagen no se mueve, gira ni redimensiona (los toques de transformación se ignoran). Se ocultan el botón de 3 puntos y el
  círculo de giro; el recuadro se ve gris con tiradores apagados y, a la izquierda del recuadro, aparece un botón de candado. Tocarlo desbloquea (vuelven el botón de
  3 puntos, el círculo y la edición). Bloquear/desbloquear es un paso del historial. Una imagen bloqueada sigue pudiéndose seleccionar (para desbloquearla).
- Todo esto solo se ve con la herramienta Seleccionar; con cualquier otra desaparece, esté o no bloqueada. Los enlaces, la opacidad, el volteo y el recorte no cambian.

## Barra de selección y selección múltiple (v0.19.0)
- Con la herramienta **Seleccionar** aparece, flotando arriba al centro de la hoja, una barra con 7 botones (izq. a der.): Seleccionar todo, Guardar como sticker
  ("Próximamente"), Copiar, Pegar, Traer al frente, Enviar al fondo y Eliminar. Se oculta con cualquier otra herramienta y durante los modos de recorte.
- **Seleccionar todo** elige los objetos (sin bloquear) de la capa actual. Con 2 o más se dibuja un recuadro de GRUPO con sus 8 tiradores y círculo de giro: mover,
  escalar (uniforme, respecto al punto opuesto) y girar el grupo (cada objeto desde su estado inicial). El botón de 3 puntos y el candado son solo de un objeto.
- **Copiar/Pegar** (`ObjectClipboard`): copias independientes (id y archivo nuevos); se pegan en la capa seleccionada al centro de la hoja, cada pegado se corre 10 mm.
- **Al frente / al fondo** reordenan la lista de objetos de la capa (= orden de dibujo, se guarda en `layers/<idNota>.json`). **Eliminar** quita los objetos de su capa.
- Todo (menos el sticker) es un paso del historial. Selección y recuadro se actualizan o desaparecen según la acción.

## Barra de selección en la barra superior (v0.19.1)
- Se quitó la barra flotante de selección. Con la herramienta **Seleccionar**, el espacio central de la barra superior (donde van las flechas de página y el contador)
  muestra los 7 íconos de selección (mismo orden); con cualquier otra herramienta vuelven las flechas y el contador. Las herramientas de la izquierda y los botones de la
  derecha no cambian. Mientras hay un recorte en curso, las acciones de selección avisan y no se aplican.

## Stickers (v0.20.0)
- `StickerStore.kt`: `Sticker(id, categoryId, path)` y `StickerCategory(id, name)`; JSON en las preferencias `stickers` (`items`, `cats`, `cur`) y archivos en
  `filesDir/stickers/`. Siempre existe la categoría "Mis stickers" (no se elimina); eliminar otra borra sus stickers.
- `StickerMenu.kt`: menú flotante (hoja inferior) que abre la **estrella** de la barra superior: **+** (galería, máx. 1024 px), selector de categoría (lista
  desplegable: elegir, nueva y eliminar) y basura (borra el sticker seleccionado). Cuadrícula de la categoría actual: toque = se inserta al centro de la hoja
  (40 %) como `ImageItem` normal, con historial; toque largo + arrastrar = reordena (se guarda); toque largo sin mover = lo selecciona y ofrece "Borrar sticker".
- **Guardar como sticker** (barra de selección): dibuja cada imagen seleccionada como se ve (recorte, giro, espejo; sin opacidad) en un PNG transparente y lo guarda
  en la categoría actual del menú. No cambia la hoja ni el historial.

## Íconos de selección solo con algo seleccionado (v0.20.1)
- `PageViewport.onSelectionChanged` avisa al editor cuando se pasa de "nada seleccionado" a "hay selección" (o al revés). Los 7 íconos de selección se muestran en el
  centro de la barra solo si la herramienta es Seleccionar **y** hay algo seleccionado; en cualquier otro caso se ve la navegación `< 1/1 >`.

## Trazos seleccionables (v0.21.0)
- Con **Seleccionar**, tocar un trazo (lápiz, pluma, pincel o marcador) lo selecciona: recuadro con 8 tiradores y círculo de giro, y el botón de 3 puntos (todas sus
  opciones dicen "Próximamente" para trazos). La barra de 7 íconos sigue siendo solo para imágenes. `StrokeItem` (`Selectables.kt`) implementa `SelectableItem`:
  caja = caja de los puntos + 0,75 × grosor; mover/redimensionar/girar con los mismos gestos de las imágenes.
- Durante el gesto el trazo se muestra transformado (matriz sobre sus puntos, desde la caja inicial a la actual). Al terminar se "hornea": el trazo queda en el mismo lugar de
  su capa con los puntos nuevos y se guarda con el archivo de trazos de siempre (por eso `LayerRepository` no cambia). No puede salirse de la hoja (las coordenadas se guardan de 0 a 655 mm).
- Mover/redimensionar/girar un trazo es un paso del historial (se restaura por referencia, sin copiar puntos). Seleccionar solo no es un paso, igual que con imágenes.
- **Borrador:** ya partía el trazo en trozos independientes (cada trozo es un trazo nuevo, y ahora se puede seleccionar por separado). Se aceleró: cada trazo guarda su caja (se
  calcula una vez) y el borrador descarta de inmediato los que quedan lejos; si nada toca el círculo no se crea nada. Tocar un trazo solo mide los trazos cuya caja está cerca.

## Selector de color: espacios, reordenar y gotero (v0.22.0)
- Colores guardados en 14 espacios FIJOS (`PenStore.slots`/`setSlots`; los datos anteriores se leen igual). Tocar un espacio lo selecciona (borde grueso; si tiene color, pasa al selector);
  **+** guarda el color actual en el espacio seleccionado (si no hay, en el primero vacío); la **basura** vacía el espacio seleccionado; mantener presionado y arrastrar reordena
  (`ItemTouchHelper` en un `RecyclerView` de 2 columnas) y se guarda al soltar.
- **Gotero:** el ícono junto al HEX activa el modo gotero: el menú deja de tomar el foco, se toca la hoja y el color de ese píxel (la hoja se dibuja en un bitmap de 1×1 y se lee
  con `getPixel`) pasa a HSV (`Color.colorToHSV`): el puntito de la rueda, la barra de valor, el HEX y el color del lápiz se actualizan. Se cancela al cambiar de herramienta u hoja.
- El selector ya era un `PopupWindow` (no mueve la hoja) anclado a los colores de la barra; se mantiene así.

## Trazos como imágenes: barra de 7 íconos, menú de 3 puntos y grupos (v0.23.0)
- **Barra de 7 íconos:** `PageViewport.hasSelection` ahora es verdadero con cualquier selección (imagen o trazo, uno o varios). Seleccionar todo toma imágenes **y** trazos
  (sin bloquear) de la capa actual. Copiar/pegar usa `ObjectClipboard` (ahora guarda también trazos: puntos relativos al centro del grupo y opacidad; los pegados son trazos
  nuevos y nacen desbloqueados). Al frente/al fondo reordena los trazos entre los trazos de su capa (las imágenes de una capa siempre van debajo de sus trazos). Eliminar quita
  trazos e imágenes. **Guardar como sticker:** los trazos seleccionados se rasterizan JUNTOS en un solo PNG transparente (cada imagen sigue siendo un sticker aparte).
- **Menú de 3 puntos:** con un trazo, *voltear horizontal/vertical* (refleja los puntos sobre el centro de su caja, en los ejes de la caja), *opacidad* y *bloquear* funcionan;
  enlace, recortar y recorte libre siguen diciendo "Próximamente". Bloqueado: contorno gris, sin tiradores y el candado ocupa el lugar de los 3 puntos (igual que una imagen).
- **Datos del trazo:** `Stroke` ahora tiene `opacity` (0..1, multiplica a la propia del tipo) y `locked`. Siguen siendo inmutables: cada cambio crea un trazo nuevo que ocupa el
  mismo lugar de su capa y conserva el `uid` (así deshacer/rehacer vuelven a seleccionarlo). `Stroke.copyWith` comparte puntos y camino calculado. El borrador y los portapapeles
  conservan opacidad y bloqueo. Pluma y pincel con opacidad < 1 se dibujan en una capa con alpha (para que sus formas no se mezclen dos veces).
- **Archivo de trazos:** nueva versión `ALN2` (por trazo: 1 byte de banderas, bit 0 = bloqueado, y 1 byte de opacidad). Se leen `ALN1` y `ALN2`; se escribe `ALN2` solo si algún
  trazo tiene opacidad o bloqueo (si no, `ALN1`, idéntico al de antes). Una versión anterior de la app no puede leer `ALN2`.
- **Grupos con trazos:** el grupo mueve/redimensiona/gira los trazos con UNA matriz (`gMat`) al dibujar (`PageView.liftedSet`/`liftMatrix`), sin recalcular puntos en cada
  movimiento; al soltar se fijan (`bakeGroupStrokes`: puntos y grosor nuevos, mismo lugar en la capa, mismo `uid`). Un grupo con trazos no puede salirse de la hoja
  (`keepGroupInPage`). Con más de 40 objetos solo se dibuja el contorno del grupo. `refreshPage` y `reselect` usan conjuntos (no buscan trazo por trazo).
- Todo (mover, voltear, opacidad, bloquear, copiar/pegar, orden, eliminar) es un paso del historial y guarda con el archivo de trazos (`onChanged`) y las capas.

## Menú de 3 puntos de los trazos: enlace, color y grosor (v0.24.0)
- Solo cambia el menú de los TRAZOS (el de las imágenes queda igual). Orden: Enlace, Voltear horizontal, Voltear vertical, Opacidad, **Color**, **Grosor**, Bloquear. Ya no tiene
  Recortar ni Recorte libre. Íconos nuevos en `Icons.kt`: `COLOR_WHEEL` (círculo cromático) y `THICKNESS` (cuatro líneas de grosor creciente).
- **Enlace:** `Linkable` (`Selectables.kt`) es la interfaz común (`linkType`, `linkData`) de `ImageItem` y `StrokeItem`; los tipos siguen siendo `ImageItem.LINK_*`. `Stroke` también guarda
  `linkType`/`linkData`. Es el mismo diálogo de las imágenes (título "Enlace del trazo"), con las mismas funciones del editor (`showLinkMenu`, `setLink`, `openLink`...). En modo dibujo
  (lápiz, marcador) un toque corto y quieto sobre un trazo con enlace lo abre (se descarta el punto que se iba a dibujar) y, si se mantiene, se ve la cadena en su esquina. Para trazos
  la tolerancia del toque es de 2 mm (para seleccionar son 2,5 mm), así un punto escrito cerca no abre el enlace. Los trazos van encima de las imágenes de su capa también para esto.
- **Color:** abre el MISMO selector de color del dibujo (`ColorPicker.show`: círculo cromático, HEX, guardados, gotero). Cada color elegido reemplaza el trazo de la capa al instante
  (`Stroke.copyWith(color = ...)`, sin recalcular el camino); al cerrar el selector se guarda y es UN paso del historial (solo si el color cambió).
- **Grosor:** submenú Chico / Mediano / Grueso con los mismos tres grosores de la barra de dibujo (marcador `hlWidthMm`: 2,5 / 5 / 9 mm; lápiz, pluma y pincel `widthMm`: 0,35 / 0,8 / 1,8 mm).
  Se marca el actual si coincide con uno. Cambia el trazo al instante, se guarda y es un paso del historial.
- **Archivo de trazos:** sigue siendo `ALN2`. Las banderas por trazo usan el bit 1 (valor 2) = "tiene enlace"; entonces, tras el byte de opacidad, van un byte de tipo y el texto (`writeUTF`;
  enlaces de más de 16000 caracteres no se guardan). Los `ALN2` de la versión anterior (sin bit 1) se leen igual. Color y grosor ya se guardaban en `ALN1` y `ALN2`. Se escribe `ALN2` solo si
  algún trazo tiene opacidad, bloqueo o enlace. Copiar/pegar (objetos y hojas), el borrador (cada trozo conserva el enlace) y mover/girar/voltear conservan el enlace.

## Formas básicas: línea, cuadrado y círculo (v0.25.0)
- **No hay un tipo de objeto nuevo:** una forma se convierte en los puntos de su contorno (`ShapeGen` en `Strokes.kt`, ≤ 0,6 mm entre puntos, como el lápiz) y se guarda como un `Stroke` normal
  (tipo 0, lápiz: grosor parejo). Por eso hereda selección, menú de 3 puntos, los 7 íconos, copiar/pegar, opacidad, enlace, bloqueo, color, grosor y el borrador (que la parte como a cualquier trazo).
- **Barra:** el ícono de formas (`Icons.SHAPES`) activa la herramienta 5. En la barra central salen línea, cuadrado y círculo (`Icons.SHAPE_LINE/RECT/CIRCLE`, la elegida resaltada) y, a la derecha, los
  mismos grosores y colores del lápiz (`PenStore`; toque largo en un color = selector). `PenOptions.buildShapes` los arma; la parte de grosor y color se movió sin cambios de `build` a `widthAndColors`,
  que usan el lápiz y las formas. La forma elegida vive en `PageViewport.shape`.
- **Dibujo (`PageViewport`):** al tocar se guarda el punto de inicio (debe estar dentro de la hoja); al arrastrar `shapeTo` genera los puntos desde el inicio hasta el punto actual y los deja como el trazo "en curso"
  (`live`), que es la vista previa; al soltar `commitLive` lo guarda en la capa activa con UN paso del historial. Línea: del inicio al punto actual. Cuadrado/rectángulo: solo el contorno, con inicio y punto actual
  como esquinas opuestas. Círculo: centro en el inicio y radio hasta el punto actual (con seno y coseno); el radio se limita para que el círculo quepa en la hoja. El otro extremo no sale de la hoja. Una forma
  de menos de 0,5 mm (un toque) no se guarda. Con la herramienta de formas no se abren enlaces al tocar (igual que con el borrador y seleccionar).

## Herramienta de texto (v0.26.0)
- **Objeto nuevo `TextItem`** (`Selectables.kt`, implementa `SelectableItem`): `text`, `fontSize`, `fontFamily` (id de `FontManager`: `sys:`, `asset:` o `file:`), `isItalic`, `isBold`, `isUnderline`,
  `color`, `x`/`y` (centro del recuadro), `rotation`, `bounds` (recuadro sin rotar), además de `opacity` y `isLocked`. Una sola línea. El tamaño está en "unidades de texto": 1 unidad =
  0,2 mm (`TextItem.MM_PER_UNIT`), tamaño 40 = letra de 8 mm; rango 8 a 200. `bounds` es siempre el tamaño NATURAL del texto (se mide con `Paint`); mientras se redimensiona el texto se
  dibuja ajustado al recuadro y al soltar `bakeScale()` pasa el cambio de escala al tamaño de la letra. Solo se redimensiona por las esquinas (conserva la proporción).
- **Datos:** `Layer.texts` (junto a `items` y `canvas`; las imágenes y los trazos no cambian). Se dibujan ENCIMA de las imágenes y DEBAJO de los trazos de su capa (el marcador se ve sobre el
  texto). Guardado: `PageState.texts` (lista de `TextDto` por capa) en `layers/<idNota>.json`; los archivos viejos no lo traen y cargan igual. Historial: `TextSnap` en `LayerSnap.texts`.
  Copiar/pegar hoja (`PageClipboard`) y las miniaturas (`PageManagerActivity`) incluyen los textos.
- **Barra (herramienta 6, la "T"):** `TextOptions.build` -> `Size N` (abre un panel con `SeekBar` y DONE; el número se actualiza al mover), fuente (hoja con la lista de `FontManager`, la misma de
  Ajustes, cada una en su tipografía; solo cambia el texto, no la fuente de la app), I, B, U (resaltados si están activos) y los cuatro colores del lápiz (`PenOptions.colorsOnly`).
  El estilo se guarda en `TextStore` (SharedPreferences `text`: size, family, italic, bold, underline; el color es el del lápiz).
- **Escritura (`PageViewport`):** con la herramienta texto, un toque corto y quieto en un espacio vacío abre un `EditText` transparente en ese punto (con teclado) y, sobre un texto existente, lo
  abre para editarlo (se carga su estilo en la barra). Se ve con el tamaño, fuente, estilos y color de la barra y los cambios de la barra se aplican al instante (`textStyleChanged`). Enter, o tocar
  fuera del cuadro (en la hoja), cierra el cuadro y deja un `TextItem` (`finishText`; también al cambiar de herramienta u hoja y en `onPause`). Un texto nuevo vacío no se crea; uno existente que se
  deja vacío se borra; si no cambió nada no hay paso en el historial. Crear o editar es UN paso de deshacer/rehacer. Mientras se escribe, la ventana usa `ADJUST_PAN` para que el teclado no encoja la hoja.
- **Selección:** con Seleccionar el texto se toca, se mueve, se gira y se redimensiona como una imagen; el menú de 3 puntos y la barra de 7 íconos aparecen. Por ahora, con un texto seleccionado, todas las
  opciones del menú y los íconos estrella, copiar, al frente y al fondo dicen "Próximamente"; sí funcionan eliminar, pegar y seleccionar todo (que no incluye textos). Los textos no entran en la selección múltiple.
- **Archivos tocados:** `Selectables.kt`, `Strokes.kt`, `History.kt`, `LayerRepository.kt`, `PageClipboard.kt`, `PageManagerActivity.kt`, `PageViewport.kt`, `EditorActivity.kt`, `PenOptions.kt`,
  `Stores.kt`, `FontManager.kt` (`typefaceFor`) y el archivo nuevo `TextOptions.kt`.

## Texto completo: barra de 7 íconos, menú de 3 puntos y arreglo del cuadro invisible (v0.27.0)
- **Arreglo del texto invisible al escribir:** `PageView` tiene `elevation = 4 dp` y Android dibuja ENCIMA lo que tiene más elevación sin importar el orden de los hijos; el `EditText` flotante tenía 0,
  así que quedaba debajo de la hoja (el color no era el problema). Ahora `et.elevation = dp(10)` (sin fondo, no hay sombra). Además `rebuild()` ya no pierde el cuadro si la vista se reconstruye
  (lo vuelve a añadir y lo recoloca).
- **Barra de 7 íconos con textos:** Seleccionar todo incluye textos (imágenes, textos y trazos sin bloquear). Copiar/pegar clona el `TextItem` con todas sus propiedades (`ObjectClipboard`: `ClipTextObj`;
  los pegados nacen desbloqueados y con id nuevo). Al frente/al fondo reordena los textos entre los textos de su capa (imágenes < textos < trazos). Eliminar ya borraba textos. Guardar como sticker
  rasteriza cada texto seleccionado en un PNG transparente aparte (`renderTextForSticker`: con giro y espejo, sin opacidad). Un grupo con textos se mueve/escala/gira como uno de imágenes; al soltar
  `bakeScale()` pasa la escala al tamaño de la letra de cada texto.
- **Menú de 3 puntos del texto:** Enlace, Voltear horizontal, Voltear vertical, Opacidad y Bloquear funcionan (sin Recortar ni Recorte libre, que son de imágenes). `TextItem` ahora implementa `Linkable`
  (`linkType`/`linkData`, mismo diálogo de enlace con título "Enlace del texto"), tiene `isFlippedH/V` (el espejo se aplica al dibujar, en los ejes del texto) y `opacity`/`isLocked` ya existían. Tocar un
  texto con enlace con lápiz, marcador o sin herramienta lo abre (`linkAt`); con la herramienta texto, tocarlo lo edita.
- **Guardado:** `TextDto` (layers/<idNota>.json) ahora guarda también `fh`, `fv`, `lt`, `ld` (con valores por defecto: los archivos de la versión anterior cargan igual); `TextSnap` (historial) y
  `ClipText` (copiar hoja) igual. Todo cambio (copiar, pegar, mover, voltear, opacidad, bloquear, enlace, orden) es UN paso de deshacer/rehacer y se guarda con las capas.

## Maquetación del texto: ancho fijo, espaciado, alineado, altura y tiradores laterales (v0.28.0)
- **Modelo (`TextItem`):** nuevas propiedades `isFixedWidth` (false), `letterSpacing` (0 a 0,5 em), `textAlign` (0 izquierda, 1 centro, 2 derecha) y `lineHeight` (0,2 a 2). Se guardan en el JSON de capas
  (`TextDto`: `fw`, `ls`, `ta`, `lh`, con valores por defecto: los archivos anteriores cargan igual), en el historial (`TextSnap`) y en los dos portapapeles (`ClipText`, `ClipTextObj`).
- **Dibujo:** ya no es `drawText`: `TextItem` arma un `StaticLayout` (a 50 px por mm, dibujado reducido; se reutiliza mientras no cambie nada) con salto de línea, alineado, `letterSpacing` y altura de línea
  (`setLineSpacing(0, lineHeight)`, sin relleno de fuente). Sin ancho fijo: una línea y el recuadro se adapta solo al texto (`refit`). Con ancho fijo: el ancho del recuadro es el que se le dio, el texto salta de
  línea al llegar a su borde derecho y el alto es el del texto (o uno mayor si se arrastró un tirador de arriba o de abajo; el texto queda arriba). El recuadro cubre las letras aunque la altura de línea sea menor que 1.
  Todo ajuste del recuadro conserva su esquina superior izquierda (con el giro). Al editar un texto de ancho fijo, el cuadro de escritura tiene su ancho y salta de línea igual (Enter sigue terminando).
- **Menú de 3 puntos del texto** (después de Opacidad y antes de Bloquear): *Fix width / Unfix width* (alterna `isFixedWidth`; íconos `FIX_ON` = flechas hacia afuera con topes, `FIX_OFF` = flecha doble normal),
  *Espaciado* (mini panel con SeekBar 0,00 a 0,50), *Alineado* (tres íconos: izquierda, centro, derecha; el actual resaltado) y *Altura* (SeekBar 0,20 a 2,00). Los controles cambian el texto en tiempo real y cada
  vez que se abre un panel cuenta como UN paso del historial; todo se guarda con las capas. El alineado se nota con varias líneas (ancho fijo).
- **Tiradores:** el texto ya tiene los 8: las esquinas escalan (cambian el tamaño de la letra y, si el ancho es fijo, el ancho y el alto reservado en la misma proporción), izquierda y derecha cambian el ancho (el texto pasa
  a ancho fijo y se reacomoda) y arriba y abajo cambian el alto del recuadro (nunca menos que el del texto; también pasa a ancho fijo). `PageViewport.startManip` avisa al texto qué se arrastra (`beginGesture`: `G_CORNER`,
  `G_SIDE`, `G_VERT` o `G_NONE`) y al soltar `endGesture()` pasa lo hecho a sus datos; los textos de un grupo se escalan con él. Imágenes, trazos y formas usan el mismo código de siempre.

## Limpieza de barra y panel de capas, y numeración de capas (v0.29.0)
- **Barra superior:** se quitó el candado (no tenía función). En su lugar queda un hueco invisible del mismo ancho (44 dp): si no, el espaciador de la izquierda lo absorbería y el ojo (y lo que va a su derecha)
  se movería. El ojo y el resto de la barra quedan exactamente donde estaban.
- **Panel de capas (`LayersPanel`):** se quitaron los íconos Recorte y Bloqueo alfa y la franja que los contenía (con su línea); la opacidad (100%) queda igual.
- **Nombres:** la lista ya no muestra `Layer.name` ("Capa N", que sigue guardado igual) sino solo el número de la posición: la capa de más abajo (índice 0, la primera en dibujarse) es la 1 y la de arriba la más alta
  (`numberOf`: índice en el modelo + 1; la lista se muestra al revés). Se recalcula siempre desde el índice, así que al crear, borrar o reordenar no hay saltos (si se borra la 2, la 3 pasa a ser la 2). Al arrastrar,
  `refreshNumbers()` actualiza los números de las filas visibles sin rehacerlas; al agregar, borrar o deshacer se rehace la lista. El diálogo de borrar dice "la capa N". El orden de dibujo no cambió: el índice 0 se
  dibuja primero y arrastrar sigue moviendo la capa en el modelo (`PageLayers.move`), así que el Z-index coincide con la numeración.

## Capas completas: copiar, pegar, unir, limpiar, mover, bloquear y modos de fusión (v0.30.0)
- **Panel (`LayersPanel`):** izquierda, de arriba abajo: agregar, copiar capa, pegar capa, incorporar la siguiente (unir hacia abajo). Derecha: limpiar, mover capa, eliminar y menú ⋮. La numeración (1, 2, 3...
  por posición, la de abajo es la 1) se recalcula sola tras cada acción.
- **Copiar / pegar capa (`LayerClipboard.kt`, nuevo):** copia profunda de la capa y todos sus objetos (trazos con puntos nuevos, imágenes copiadas a `layerclip/`, textos). Pegar crea una capa nueva JUSTO ENCIMA de
  la actual con objetos de ids nuevos (trazos nuevos, ids nuevos de imágenes y textos, archivos de imagen nuevos en `images/<idNota>/`), así que nada se comparte; se puede pegar varias veces y en otra hoja o nota.
  Usa las clases de `PageClipboard` (`ClipLayer`, `ClipImage`, `ClipText`).
- **Unir hacia abajo (`PageLayers.mergeDown`):** trazos, imágenes y textos de la capa actual pasan, encima de lo que ya tenía, a la de abajo y la actual se elimina (queda seleccionada la de abajo). No se puede si no hay capa
  debajo o si alguna de las dos está bloqueada. Los objetos toman la opacidad y el modo de fusión de la capa de abajo.
- **Limpiar (`PageLayers.clearSelected`):** borra trazos, imágenes y textos de la capa y la deja vacía (pide confirmación si tenía algo; no en capas bloqueadas). Eliminar una capa bloqueada pide desbloquearla antes.
- **Mover capa:** el botón activa un modo (resaltado) en el que arrastrar un dedo por la hoja mueve toda la capa seleccionada. `PageViewport`: `layerMoveMode`, `M_LMOVE`, `beginLayerMove/layerMove/endLayerMove`. Los trazos
  se dibujan con una matriz (como en un grupo) y se fijan al soltar (mismo orden e id); imágenes y textos mueven su recuadro en vivo. Los trazos mandan: la capa no sale de la hoja (coordenadas de 0 a 655 mm). Un paso del
  historial, se guarda con trazos y capas. Cerrar el panel o elegir una herramienta sale del modo; dos dedos siguen haciendo zoom.
- **Menú ⋮:** Modo de fusión (Normal, Multiplicar, Superponer, Trama) con la actual marcada, y un interruptor "Bloquear capa". Cada cambio es un paso del historial.
- **Modelo y guardado:** `Layer.blendMode` (0 normal, 1 multiplicar, 2 superponer, 3 trama) y `Layer.isLocked` se guardan en el JSON de capas (los archivos anteriores cargan como normal y desbloqueada), en el historial
  (`LayerSnap`) y al copiar hojas (`ClipLayer`). Dibujo: `LayerBlend.begin` abre la capa aparte y la combina con su opacidad y un `PorterDuffXfermode` (hoja y miniaturas); sin modo y con opacidad completa no cuesta nada.
- **Capa bloqueada:** no se dibuja ni se borra en ella (lápiz, marcador, formas, borrador), no se escribe texto, no se pega ni se insertan imágenes o stickers, no se seleccionan ni mueven sus objetos (al bloquearla se suelta la
  selección), y Seleccionar todo no la toma. Sí se pueden abrir sus enlaces y verla. La fila de la capa muestra un candado.

## Opacidad de capa funcional (v0.31.0)
- `Layer.opacity` (0 a 1) ya existía: se guardaba en el JSON de capas y en el historial, y la hoja ya la aplicaba al dibujar la capa como UN bloque (`LayerBlend.begin`: `saveLayerAlpha`, o `saveLayer` con
  el modo de fusión y la opacidad en el `Paint`). Lo que faltaba era la barra del panel, que era solo visual. No cambió el modelo ni el dibujo.
- **Barra (`LayersPanel`):** siempre muestra la opacidad real de la capa seleccionada (`syncOpacity`, que se llama en `fillList`: al cambiar de capa, agregar, eliminar, pegar, unir, limpiar, deshacer, cambiar de hoja...).
  Al moverla, la opacidad de la capa cambia en tiempo real (`onOpacityLive` -> `viewport.refreshPage()`, solo redibuja) y se guarda UNA vez al soltar (`commitOpacity` -> `onVisibilityChanged`: redibuja y guarda el JSON de
  capas; también a los 400 ms si no llega el aviso de soltar). Cada movimiento de la barra es UN paso del historial (foto de la hoja de antes, `PageSnap`). Se puede cambiar la opacidad de una capa bloqueada.
- No afecta a los modos de fusión (la misma capa de dibujo lleva opacidad y modo) ni al bloqueo.

## Imagen de fondo de la hoja (v0.32.0)
- **No crea una hoja nueva:** pone una imagen de la galería como fondo de la hoja actual (que ya tiene su tamaño). Se escala con una `Matrix` tipo **centerCrop** (se estira hasta cubrir toda la hoja sin deformarse y se recorta lo que
  sobra) y se dibuja primero en `PageView.onDraw`: encima del color de la hoja y debajo de todas las capas, trazos, imágenes y textos (no es una capa: no se selecciona ni se mueve; el bloqueo de capas no la afecta).
- **Menú ⋮ del panel:** con la capa de hasta abajo (la 1) seleccionada aparece "Insertar imagen de fondo" y, si la hoja ya tiene una, "Quitar imagen de fondo". Insertar abre la galería (`REQ_BACKGROUND`, `ACTION_OPEN_DOCUMENT`);
  solo en la vista de una hoja (como las demás herramientas de edición).
- **Almacenamiento:** `EditorActivity.setBackgroundFromUri` copia la imagen a `images/<idNota>/` (reducida a 2048 px, respetando EXIF, JPEG o PNG si tiene transparencia) con `ImageUtil.saveFromUri`.
- **Modelo y JSON:** `PageLayers.backgroundImagePath` (nombre del archivo, no la ruta completa, para no depender de dónde esté la carpeta de la app) y `PageLayers.bgDir` (solo en memoria, se asigna al cargar). Se guarda en
  `PageState.backgroundImagePath` del JSON de capas (los archivos anteriores no lo traen y cargan sin fondo). No forma parte del historial de deshacer (deshacer no quita ni pone el fondo).
- **Carga sin frenar el dibujo (`PageBackground.kt`, nuevo):** `get` devuelve el bitmap si está en la caché de `ImageUtil` (nuevas `peek` y `forget`); si no, lo decodifica en un hilo (1024 px en la vista continua, 2048 en la de una
  hoja) y la hoja se vuelve a dibujar al terminar. Un archivo ilegible no se reintenta en cada cuadro.
- **Limpieza de archivos:** al reemplazar o quitar el fondo se borra el archivo viejo (y se saca de la caché). `cleanOrphanImages` (editor) y `cleanupImages` (gestor de hojas) cuentan el fondo como archivo en uso, así que no se
  borra por error, y al borrar una hoja su fondo queda huérfano y se limpia; al borrar la nota se borra toda la carpeta `images/<idNota>/`. Copiar/pegar hojas (`PageClipboard`: `ClipPage.bg`) copia el archivo de fondo con
  nombre nuevo (nada se comparte entre hojas). Las miniaturas del gestor de hojas también dibujan el fondo.

## Corrección del menú ⋮ del panel de capas (v0.32.1)
- **Problema:** con la capa 1 seleccionada el menú tiene más filas (fusión, bloqueo, insertar/quitar imagen de fondo: unos 340 a 380 dp) y el botón ⋮ queda abajo en el panel; como la ventana no cabía hacia abajo, Android la recortaba y las filas
  del final (las del fondo) quedaban fuera de la pantalla. La condición (`m.selected == 0`, el índice 0 del modelo = la capa 1) y las filas estaban bien.
- **Arreglo (`LayersPanel.showMenu`):** se mide el contenido, la ventana se coloca arriba o abajo del botón según donde haya más espacio y, si aun así no cabe, tiene el alto disponible y el contenido se desplaza (`ScrollView`).
  El modo de fusión y el bloqueo no cambiaron.

## Fondo de hoja estirado (v0.32.2)
- La imagen de fondo ya no usa centerCrop: se estira (fitXY) para cubrir EXACTAMENTE el rectángulo de la hoja, sin conservar la proporción (se deforma si no coincide). `PageView.onDraw` hace
  `canvas.drawBitmap(bitmap, null, rect, paint)` con `rect` = los límites de la hoja (se quitó la `Matrix`). Las miniaturas del gestor de hojas usan el mismo estirado. Nada más cambió (capas, trazos, guardado).
  (Esto reemplaza lo dicho de centerCrop en la sección v0.32.0.)

## Compilar
Sube el proyecto a GitHub: el workflow `.github/workflows/build.yml` (Build APK, JDK 17, Gradle 8.9)
genera el APK en la pestaña Actions → artefacto `AloNotes-debug`.
